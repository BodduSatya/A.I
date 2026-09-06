"""
Tools the agents call. These are deliberately backed by local JSON/Markdown
fixtures instead of real Prometheus/Datadog/Confluence/Slack so the whole
project runs with zero external infra. Swapping a tool's internals for a
real API call is a one-function change - see the docstrings.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import List

DATA_DIR = Path(__file__).resolve().parent.parent / "data"


def _load_json(name: str):
    with open(DATA_DIR / name, "r", encoding="utf-8") as f:
        return json.load(f)


def get_recent_logs(service: str, limit: int = 20) -> str:
    """Simulates pulling recent logs for a service (stand-in for a Loki/ELK query)."""
    logs = _load_json("logs.json").get(service, [])
    lines = logs[-limit:]
    if not lines:
        return f"No recent logs found for service '{service}'."
    return "\n".join(f"[{l['ts']}] {l['level']}: {l['message']}" for l in lines)


def get_recent_metrics(service: str) -> str:
    """Simulates pulling recent metric samples (stand-in for a Prometheus query)."""
    metrics = _load_json("metrics.json").get(service)
    if not metrics:
        return f"No metric data found for service '{service}'."
    parts = []
    for name, series in metrics.items():
        recent = series[-5:]
        parts.append(f"{name}: {recent}")
    return "\n".join(parts)


def search_runbooks(query: str, top_k: int = 2) -> str:
    """
    Naive keyword search over local markdown runbooks (stand-in for a real
    vector search / RAG pipeline over a Confluence or Notion space).
    """
    runbook_dir = DATA_DIR / "runbooks"
    scored = []
    query_terms = set(query.lower().split())
    for path in runbook_dir.glob("*.md"):
        text = path.read_text(encoding="utf-8")
        score = sum(text.lower().count(term) for term in query_terms)
        if score > 0:
            scored.append((score, path.name, text))
    scored.sort(key=lambda x: x[0], reverse=True)
    top = scored[:top_k]
    if not top:
        return "No matching runbooks found."
    return "\n\n---\n\n".join(f"# {name}\n{text}" for _, name, text in top)


def search_incident_history(service: str, alert_type: str) -> str:
    """Searches past incidents for the same service/alert_type combination."""
    history = _load_json("incident_history.json")
    matches = [
        h for h in history
        if h["service"] == service and h["alert_type"] == alert_type
    ]
    if not matches:
        return "No similar past incidents found."
    out = []
    for m in matches:
        out.append(
            f"- {m['date']}: {m['summary']} | Resolution: {m['resolution']} "
            f"| Outcome: {m['outcome']}"
        )
    return "\n".join(out)


def execute_remediation(service: str, action: str) -> str:
    """
    Simulates executing a low-risk remediation action (e.g. restarting a
    pod, clearing a cache, scaling replicas). In production this would call
    kubectl / a deploy API / an internal ops endpoint.
    """
    return (
        f"[SIMULATED ACTION] Executed '{action}' on service '{service}'. "
        f"Result: success (mock)."
    )


def send_escalation(summary: str) -> str:
    """
    Simulates paging a human (stand-in for a Slack/PagerDuty webhook).
    Writes to a local pending-review queue that the FastAPI dashboard reads.
    """
    queue_path = DATA_DIR / "pending_escalations.json"
    queue: List[dict] = []
    if queue_path.exists():
        queue = json.loads(queue_path.read_text(encoding="utf-8"))
    queue.append({"summary": summary})
    queue_path.write_text(json.dumps(queue, indent=2), encoding="utf-8")
    return "Escalation queued for human review."
