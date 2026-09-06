"""
Wires the agent nodes into a graph:

    START
      -> diagnostics_agent
      -> history_agent
      -> planner_agent
      -> [conditional]
           - auto_remediate -> auto_remediation_agent -> END
           - escalate       -> escalation_agent -> interrupt() -> apply_human_decision -> END

The interrupt() call pauses execution and persists state via the checkpointer.
A human calls resume_incident() (wired to a FastAPI endpoint) to continue.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import interrupt

from app.state import IncidentState
from app import agents

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "checkpoints.sqlite"


def apply_human_decision(state: IncidentState) -> dict:
    """
    Pauses the graph and waits for a human to approve/deny the escalated plan.
    Whatever value is passed to `Command(resume=...)` by the caller becomes
    the return value of `interrupt()` here.
    """
    decision = interrupt(
        {
            "question": "Approve the proposed remediation plan?",
            "plan": state.get("remediation_plan"),
            "risk_level": state.get("risk_level"),
            "confidence": state.get("confidence"),
        }
    )
    return {
        "human_decision": decision,
        "trace": [f"[apply_human_decision] human said: {decision}"],
    }


def build_graph():
    graph = StateGraph(IncidentState)

    graph.add_node("diagnostics_agent", agents.diagnostics_agent)
    graph.add_node("history_agent", agents.history_agent)
    graph.add_node("planner_agent", agents.planner_agent)
    graph.add_node("auto_remediation_agent", agents.auto_remediation_agent)
    graph.add_node("escalation_agent", agents.escalation_agent)
    graph.add_node("apply_human_decision", apply_human_decision)

    graph.add_edge(START, "diagnostics_agent")
    graph.add_edge("diagnostics_agent", "history_agent")
    graph.add_edge("history_agent", "planner_agent")

    graph.add_conditional_edges(
        "planner_agent",
        agents.route_after_planning,
        {
            "auto_remediate": "auto_remediation_agent",
            "escalate": "escalation_agent",
        },
    )

    graph.add_edge("auto_remediation_agent", END)
    graph.add_edge("escalation_agent", "apply_human_decision")
    graph.add_edge("apply_human_decision", END)

    # check_same_thread=False because FastAPI can serve requests from
    # different threads than the one that opened this connection.
    conn = sqlite3.connect(str(DB_PATH), check_same_thread=False)
    checkpointer = SqliteSaver(conn)
    compiled = graph.compile(checkpointer=checkpointer)
    return compiled


# Single shared compiled graph instance used by the API layer.
triage_graph = build_graph()
