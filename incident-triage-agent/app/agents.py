"""
Each function here is one LangGraph node. A node receives the current
IncidentState, does its job (usually: build a prompt, call the LLM, maybe
call a tool), and returns a dict of the fields it wants to update.

The LLM client is created lazily so the project can be imported/tested
without an API key set (useful for the --mock demo mode).
"""

from __future__ import annotations

import json
import os
import re
from typing import Optional

from app.state import IncidentState
from app import tools

_llm = None


def get_llm():
    """Lazily construct the chat model. Swap the provider here if needed."""
    global _llm
    if _llm is not None:
        return _llm
    from langchain_anthropic import ChatAnthropic

    _llm = ChatAnthropic(
        model=os.getenv("TRIAGE_MODEL", "claude-sonnet-4-6"),
        temperature=0,
        max_tokens=1024,
    )
    return _llm


def _mock_mode() -> bool:
    return os.getenv("TRIAGE_MOCK_LLM", "false").lower() == "true"


def _ask(system: str, user: str, alert_type: Optional[str] = None) -> str:
    """Single-turn helper: send a system+user prompt, return text."""
    if _mock_mode():
        return _mock_response(system, alert_type)
    llm = get_llm()
    resp = llm.invoke(
        [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]
    )
    return resp.content if isinstance(resp.content, str) else str(resp.content)


# Scenario-aware canned outputs, keyed by alert_type, so the demo can show
# BOTH the auto-remediate and escalate branches without a real LLM. Branching
# on alert_type (ground truth from the incident, not sniffed from noisy
# concatenated prompt text) keeps this reliable regardless of what the
# runbook keyword search happens to pull in.
_MOCK_DIAGNOSTICS = {
    "high_error_rate": "Elevated 5xx error rate correlates with DB connection pool exhaustion and rising p95 latency.",
    "oom_crash_loop": "Memory usage is pinned near 100% and the pod is being OOMKilled repeatedly; this is a recurring crash loop, not a transient blip.",
    "high_latency": "p95 latency is climbing steadily with no matching rise in error rate yet - an early warning signal.",
}

_MOCK_HISTORY = {
    "high_error_rate": "Similar incident found; restarting the connection pool resolved it previously.",
    "oom_crash_loop": "A past OOM crash loop on this service required a deploy rollback, not a restart - restarting alone did not help.",
    "high_latency": "No exact match in history; runbook flags this service as high blast-radius if it handles auth/payment traffic.",
}

_MOCK_PLAN = {
    "high_error_rate": {
        "plan": "Restart the database connection pool for the affected service.",
        "confidence": 0.86,
        "risk_level": "low",
    },
    "oom_crash_loop": {
        "plan": "Recurring OOM crash loop - roll back the last deploy rather than just restarting the pod.",
        "confidence": 0.4,
        "risk_level": "high",
    },
    "high_latency": {
        "plan": "Restart the service to clear potential stuck connections; escalate if it recurs within 15 minutes.",
        "confidence": 0.55,
        "risk_level": "medium",
    },
}

_MOCK_ESCALATION = {
    "oom_crash_loop": "Escalation summary: recommendation-service is stuck in an OOM crash-loop. Diagnostics show memory pinned near 100% with repeated OOMKills. History shows a restart alone did not fix this pattern before - it needed a rollback. Confidence in a safe auto-fix is low and the risk is high, so this needs human sign-off before any action is taken.",
    "high_latency": "Escalation summary: auth-service p95 latency is climbing with no errors yet. No exact historical match was found, and this service carries high blast-radius (auth traffic). Confidence is moderate and risk is medium, so a human should confirm before a restart is attempted.",
}


def _mock_response(system: str, alert_type: Optional[str]) -> str:
    """
    Deterministic canned responses so the whole graph can be demoed/tested
    without hitting a real LLM API. Triggered by TRIAGE_MOCK_LLM=true.
    """
    key = alert_type if alert_type in _MOCK_PLAN else "high_error_rate"

    if "planning agent" in system.lower():
        return json.dumps(_MOCK_PLAN[key])
    if "history-lookup agent" in system.lower():
        return _MOCK_HISTORY[key]
    if "escalation summaries" in system.lower():
        return _MOCK_ESCALATION.get(key, _MOCK_ESCALATION["oom_crash_loop"])
    if "diagnostics agent" in system.lower():
        return _MOCK_DIAGNOSTICS[key]
    return "OK"


# ---------------------------------------------------------------------------
# Nodes
# ---------------------------------------------------------------------------


def diagnostics_agent(state: IncidentState) -> dict:
    logs = tools.get_recent_logs(state["service"])
    metrics = tools.get_recent_metrics(state["service"])

    system = (
        "You are a diagnostics agent for on-call incident triage. "
        "Given raw logs and metrics, summarize what is abnormal in 2-4 sentences. "
        "Be concrete: name the metric/error pattern, don't just say 'something is wrong'."
    )
    user = (
        f"Service: {state['service']}\n"
        f"Alert type: {state['alert_type']}\n\n"
        f"Recent logs:\n{logs}\n\n"
        f"Recent metrics:\n{metrics}"
    )
    summary = _ask(system, user, alert_type=state["alert_type"])

    return {
        "log_summary": logs,
        "metric_summary": metrics,
        "diagnostics_notes": summary,
        "trace": [f"[diagnostics_agent] {summary}"],
    }


def history_agent(state: IncidentState) -> dict:
    similar = tools.search_incident_history(state["service"], state["alert_type"])
    runbooks = tools.search_runbooks(f"{state['service']} {state['alert_type']} {state['description']}")

    system = (
        "You are a history-lookup agent. Summarize whether this incident has "
        "happened before and what fixed it, using the provided history and runbook text. "
        "2-3 sentences. If nothing relevant was found, say so plainly."
    )
    user = f"Similar past incidents:\n{similar}\n\nRunbook matches:\n{runbooks}"
    summary = _ask(system, user, alert_type=state["alert_type"])

    return {
        "similar_incidents": similar,
        "runbook_matches": runbooks,
        "trace": [f"[history_agent] {summary}"],
    }


def planner_agent(state: IncidentState) -> dict:
    system = (
        "You are the planning agent for an on-call incident response system. "
        "Given diagnostics and history, propose ONE concrete remediation action, "
        "a confidence score from 0.0 to 1.0, and a risk_level of low/medium/high. "
        "Risk is 'low' only for safe, reversible actions like restarting a service, "
        "clearing a cache, or scaling replicas. Anything touching data, rolling back "
        "a deploy, or changing infra config is medium or high risk. "
        "Respond ONLY with strict JSON: "
        '{"plan": "...", "confidence": 0.0, "risk_level": "low|medium|high"}'
    )
    user = (
        f"Diagnostics: {state.get('diagnostics_notes')}\n"
        f"History: {state.get('similar_incidents')}\n"
        f"Runbook guidance: {state.get('runbook_matches')}"
    )
    raw = _ask(system, user, alert_type=state["alert_type"])

    plan, confidence, risk = _parse_plan(raw)

    return {
        "remediation_plan": plan,
        "confidence": confidence,
        "risk_level": risk,
        "trace": [f"[planner_agent] plan='{plan}' confidence={confidence} risk={risk}"],
    }


def _parse_plan(raw: str) -> tuple[str, float, str]:
    """Defensively parse the planner's JSON. Never trust the LLM to be perfectly clean."""
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    candidate = match.group(0) if match else raw
    try:
        data = json.loads(candidate)
        plan = str(data.get("plan", "Unknown plan"))
        confidence = float(data.get("confidence", 0.0))
        confidence = max(0.0, min(1.0, confidence))
        risk = str(data.get("risk_level", "high")).lower()
        if risk not in ("low", "medium", "high"):
            risk = "high"
        return plan, confidence, risk
    except (json.JSONDecodeError, ValueError, TypeError):
        # Malformed output is treated as high risk / low confidence so the
        # router always falls back to human escalation rather than guessing.
        return f"Could not parse plan from LLM output: {raw[:200]}", 0.0, "high"


def route_after_planning(state: IncidentState) -> str:
    """
    Conditional edge function. This is the safety-critical decision point:
    only low-risk AND high-confidence plans get auto-remediated.
    """
    confidence = state.get("confidence") or 0.0
    risk = state.get("risk_level") or "high"
    if confidence >= 0.75 and risk == "low":
        return "auto_remediate"
    return "escalate"


def auto_remediation_agent(state: IncidentState) -> dict:
    result = tools.execute_remediation(state["service"], state["remediation_plan"])
    return {
        "decision": "auto_remediate",
        "action_result": result,
        "trace": [f"[auto_remediation_agent] {result}"],
    }


def escalation_agent(state: IncidentState) -> dict:
    system = (
        "You write concise incident escalation summaries for a human on-call "
        "engineer. In 3-4 sentences, state what's wrong, what was tried/considered, "
        "the proposed plan, and why it needs human sign-off (low confidence or high risk)."
    )
    user = (
        f"Service: {state['service']}\n"
        f"Diagnostics: {state.get('diagnostics_notes')}\n"
        f"History: {state.get('similar_incidents')}\n"
        f"Proposed plan: {state.get('remediation_plan')}\n"
        f"Confidence: {state.get('confidence')}, Risk: {state.get('risk_level')}"
    )
    summary = _ask(system, user, alert_type=state["alert_type"])
    tools.send_escalation(summary)

    return {
        "decision": "escalate",
        "escalation_summary": summary,
        "trace": [f"[escalation_agent] {summary}"],
    }
