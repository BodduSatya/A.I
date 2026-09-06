"""
State schema shared across every node in the incident-triage graph.

LangGraph passes this dict-like object from node to node. Each node
reads what it needs and returns a partial update that gets merged in.
"""

from __future__ import annotations

from typing import Annotated, List, Optional, TypedDict
import operator


class IncidentState(TypedDict):
    # ---- input ----
    incident_id: str
    service: str
    alert_type: str
    description: str

    # ---- diagnostics agent output ----
    log_summary: Optional[str]
    metric_summary: Optional[str]
    diagnostics_notes: Optional[str]

    # ---- history agent output ----
    similar_incidents: Optional[str]
    runbook_matches: Optional[str]

    # ---- planner agent output ----
    remediation_plan: Optional[str]
    confidence: Optional[float]          # 0.0 - 1.0
    risk_level: Optional[str]            # "low" | "medium" | "high"

    # ---- routing / terminal state ----
    decision: Optional[str]              # "auto_remediate" | "escalate"
    action_result: Optional[str]
    escalation_summary: Optional[str]
    human_decision: Optional[str]        # "approved" | "denied" | None

    # audit trail, appended to by every node
    trace: Annotated[List[str], operator.add]
