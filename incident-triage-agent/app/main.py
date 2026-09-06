"""
FastAPI layer around the LangGraph incident-triage graph.

Endpoints:
  POST /incidents/trigger        -> start a new incident triage run
  GET  /incidents/{id}           -> get current state / trace of a run
  POST /incidents/{id}/resume    -> human approves/denies an escalated plan
  GET  /incidents                -> list all known incident ids + status
  GET  /                         -> serves the mini dashboard
"""

from __future__ import annotations

import uuid
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from langgraph.types import Command
from pydantic import BaseModel

from app.graph import triage_graph

app = FastAPI(title="Incident Triage Agent")

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

# in-memory index of incident_id -> True/False (just so /incidents can list them
# without scanning the sqlite checkpoint db by hand)
_known_incidents: dict[str, dict] = {}


class TriggerRequest(BaseModel):
    service: str
    alert_type: str
    description: str


class ResumeRequest(BaseModel):
    decision: Literal["approved", "denied"]


@app.get("/")
def dashboard():
    return FileResponse(str(STATIC_DIR / "dashboard.html"))


@app.post("/incidents/trigger")
def trigger_incident(req: TriggerRequest):
    incident_id = str(uuid.uuid4())[:8]
    config = {"configurable": {"thread_id": incident_id}}

    initial_state = {
        "incident_id": incident_id,
        "service": req.service,
        "alert_type": req.alert_type,
        "description": req.description,
    }

    result = triage_graph.invoke(initial_state, config=config)
    _known_incidents[incident_id] = {"service": req.service, "alert_type": req.alert_type}

    return {
        "incident_id": incident_id,
        "status": "awaiting_human" if result.get("__interrupt__") else "resolved",
        "state": _serialize(result),
    }


@app.get("/incidents/{incident_id}")
def get_incident(incident_id: str):
    config = {"configurable": {"thread_id": incident_id}}
    snapshot = triage_graph.get_state(config)
    if snapshot is None or not snapshot.values:
        raise HTTPException(status_code=404, detail="Incident not found")
    return {
        "incident_id": incident_id,
        "status": "awaiting_human" if snapshot.next else "resolved",
        "state": _serialize(snapshot.values),
    }


@app.post("/incidents/{incident_id}/resume")
def resume_incident(incident_id: str, req: ResumeRequest):
    config = {"configurable": {"thread_id": incident_id}}
    snapshot = triage_graph.get_state(config)
    if snapshot is None or not snapshot.values:
        raise HTTPException(status_code=404, detail="Incident not found")
    if not snapshot.next:
        raise HTTPException(status_code=400, detail="Incident is not awaiting human input")

    result = triage_graph.invoke(Command(resume=req.decision), config=config)
    return {
        "incident_id": incident_id,
        "status": "resolved",
        "state": _serialize(result),
    }


@app.get("/incidents")
def list_incidents():
    out = []
    for incident_id, meta in _known_incidents.items():
        config = {"configurable": {"thread_id": incident_id}}
        snapshot = triage_graph.get_state(config)
        status = "awaiting_human" if (snapshot and snapshot.next) else "resolved"
        out.append({"incident_id": incident_id, "status": status, **meta})
    return out


def _serialize(state: dict) -> dict:
    """Drop internal LangGraph keys before returning to the client."""
    return {k: v for k, v in state.items() if not k.startswith("__")}
