"""
Runs the full graph in mock-LLM mode (TRIAGE_MOCK_LLM=true) so tests are
fast, free, and deterministic. Also unit-tests the router and plan parser
directly since those are the safety-critical pieces of this project.
"""

import os
import uuid

os.environ["TRIAGE_MOCK_LLM"] = "true"

from app.agents import route_after_planning, _parse_plan  # noqa: E402
from app.graph import triage_graph  # noqa: E402


def test_router_auto_remediates_high_confidence_low_risk():
    state = {"confidence": 0.9, "risk_level": "low"}
    assert route_after_planning(state) == "auto_remediate"


def test_router_escalates_low_confidence():
    state = {"confidence": 0.3, "risk_level": "low"}
    assert route_after_planning(state) == "escalate"


def test_router_escalates_high_risk_even_with_high_confidence():
    state = {"confidence": 0.95, "risk_level": "high"}
    assert route_after_planning(state) == "escalate"


def test_parse_plan_handles_clean_json():
    raw = '{"plan": "restart pod", "confidence": 0.8, "risk_level": "low"}'
    plan, confidence, risk = _parse_plan(raw)
    assert plan == "restart pod"
    assert confidence == 0.8
    assert risk == "low"


def test_parse_plan_defaults_to_high_risk_on_malformed_output():
    plan, confidence, risk = _parse_plan("not json at all")
    assert risk == "high"
    assert confidence == 0.0


def test_full_graph_run_mock_mode_checkout_scenario():
    incident_id = str(uuid.uuid4())[:8]
    config = {"configurable": {"thread_id": incident_id}}
    result = triage_graph.invoke(
        {
            "incident_id": incident_id,
            "service": "checkout-service",
            "alert_type": "high_error_rate",
            "description": "DB connection timeouts on checkout-service.",
        },
        config=config,
    )
    assert "diagnostics_notes" in result
    assert "remediation_plan" in result
    assert result.get("decision") in ("auto_remediate", "escalate", None)
