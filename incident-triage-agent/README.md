# Incident Triage Agent

A multi-agent, LangGraph-based system that triages production incidents the
way an on-call engineer would: gather evidence, check history, propose a
fix, and know when to auto-remediate versus escalate to a human.

This is a portfolio project built to demonstrate practical, production-minded
agentic AI design — not just "call an LLM in a loop." The centerpiece is a
**safety-critical routing decision** (auto-fix vs. escalate) backed by a real
**human-in-the-loop interrupt**, which is the part of agent design most
tutorials skip and most production systems actually need.

---

## Why this project exists

Most "agentic AI" demos are a single LLM call with a tool or two bolted on.
Real incident response can't work that way — an agent that's wrong 10% of
the time and blindly executes fixes will eventually take down a service.
This project models the thing that actually matters: **deciding when the
agent is allowed to act on its own, and when it must stop and wait for a
human.**

## Architecture

```
POST /incidents/trigger
        │
        ▼
 ┌─────────────────┐
 │ diagnostics_agent│  reads logs + metrics, summarizes what's abnormal
 └────────┬─────────┘
          ▼
 ┌─────────────────┐
 │  history_agent   │  searches past incidents + runbooks (RAG-style lookup)
 └────────┬─────────┘
          ▼
 ┌─────────────────┐
 │  planner_agent   │  proposes ONE remediation action + confidence + risk
 └────────┬─────────┘
          │
          ▼
   [conditional router]
   confidence ≥ 0.75 AND risk == "low" ?
          │                        │
         YES                       NO
          │                        │
          ▼                        ▼
 ┌────────────────────┐   ┌────────────────────┐
 │ auto_remediation_   │   │  escalation_agent   │
 │ agent (executes fix)│   │ (drafts summary,     │
 └────────┬────────────┘   │  pages a human)      │
          │                └──────────┬───────────┘
          ▼                           ▼
         END              interrupt() — graph PAUSES here,
                           state is persisted to SQLite
                                       │
                     human calls POST /incidents/{id}/resume
                                       │
                                       ▼
                          apply_human_decision → END
```

Built with:
- **LangGraph** — the agent graph, conditional routing, and the
  `interrupt()` human-in-the-loop primitive, checkpointed to SQLite so a
  paused incident survives a server restart.
- **LangChain + Claude** (via `langchain-anthropic`) — the LLM calls each
  agent node makes.
- **FastAPI** — REST layer to trigger incidents, inspect state, and approve
  or deny escalated plans.
- **A tiny HTML dashboard** (`static/dashboard.html`) — trigger scenarios
  and approve/deny escalations without touching curl.

### Why this design is safety-conscious, not just functional

- **Routing is a hard threshold, not a vibe.** `route_after_planning()` in
  `app/agents.py` only allows auto-remediation when confidence ≥ 0.75 *and*
  risk is explicitly "low." Anything else — including a confidently-proposed
  but risky action — gets escalated.
- **Malformed LLM output fails safe.** If the planner's JSON doesn't parse,
  `_parse_plan()` treats it as `confidence=0.0, risk=high`, which always
  routes to escalation. The agent never guesses its way into an action.
- **The interrupt is a real pause, not a fake one.** `apply_human_decision`
  calls LangGraph's `interrupt()`, which suspends the graph and persists its
  state to disk via the SQLite checkpointer. You can kill the server while
  an incident is `awaiting_human` and it'll still be there when you restart.

## Mock data instead of real infra

This project runs with **zero external dependencies** — no real
Prometheus/Loki/Confluence/Slack. All of that is simulated with local files
under `data/`:

| Real system it stands in for | Local stand-in |
|---|---|
| Prometheus / metrics backend | `data/metrics.json` |
| Loki / ELK log aggregator | `data/logs.json` |
| Confluence / Notion runbooks | `data/runbooks/*.md` (keyword-searched) |
| Incident history / postmortem DB | `data/incident_history.json` |
| PagerDuty / Slack paging | writes to `data/pending_escalations.json` |

Swapping any of these for a real integration is a one-function change in
`app/tools.py` — the agent nodes don't need to know or care.

## Running without an API key (mock LLM mode)

Every agent's LLM call can be replaced with a deterministic canned response,
keyed off the incident's `alert_type` (ground truth, not string-sniffing),
so you can demo or test the **entire graph — including both the
auto-remediate and escalate branches** — without spending a single token.

Set `TRIAGE_MOCK_LLM=true` (see `.env.example`) to enable it.

---

## Setup

### Prerequisites
- Python 3.11+
- (Optional) An Anthropic API key, if you want the agents to use a real LLM
  instead of mock mode

### 1. Clone and install

```bash
git clone <your-repo-url>
cd incident-triage-agent
python3 -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env`:
- To run with a **real LLM**: set `ANTHROPIC_API_KEY` and leave
  `TRIAGE_MOCK_LLM=false`.
- To run with **no API key** (mock mode, recommended for a first look):
  set `TRIAGE_MOCK_LLM=true`.

### 3. Run the server

```bash
uvicorn app.main:app --reload
```

Open **http://localhost:8000** for the dashboard, or use the API directly
(see below). The dashboard lets you pick one of three seeded scenarios,
trigger it, and approve/deny any escalation — refreshes automatically every
5 seconds.

### 4. Run the tests

```bash
TRIAGE_MOCK_LLM=true pytest tests/ -v
```

All tests run in mock mode by design — no API key required, no network
calls, fully deterministic.

### 5. Run with Docker (one command)

```bash
docker compose up --build
```

Defaults to mock mode. To use a real LLM, either export
`ANTHROPIC_API_KEY` and `TRIAGE_MOCK_LLM=false` before running, or edit
`docker-compose.yml` directly.

---

## API reference

| Method | Path | Description |
|---|---|---|
| `POST` | `/incidents/trigger` | Starts a new triage run. Body: `{"service": "...", "alert_type": "...", "description": "..."}` |
| `GET` | `/incidents/{id}` | Returns the current state and trace of a run |
| `POST` | `/incidents/{id}/resume` | Human approves/denies an escalated plan. Body: `{"decision": "approved" \| "denied"}` |
| `GET` | `/incidents` | Lists all known incidents with status |

### Example: trigger an incident

```bash
curl -X POST http://localhost:8000/incidents/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "service": "checkout-service",
    "alert_type": "high_error_rate",
    "description": "Spike in 5xx errors and DB connection timeouts."
  }'
```

## Seeded demo scenarios

Three scenarios are pre-loaded in `fixtures/sample_incidents.json` and in
the dashboard dropdown, chosen to exercise every branch of the graph:

1. **`checkout_pool_exhaustion`** — DB connection pool exhaustion with a
   clean historical match → **auto-remediates** (low risk, high confidence).
2. **`recommendation_oom_loop`** — recurring OOM crash loop, runbook
   explicitly warns this needs a rollback → **escalates** (high risk).
3. **`auth_latency_warning`** — rising latency on a high-blast-radius auth
   service, no clean historical match → **escalates** (medium risk).

## Project structure

```
incident-triage-agent/
├── app/
│   ├── main.py        # FastAPI routes
│   ├── graph.py        # LangGraph StateGraph wiring + interrupt logic
│   ├── agents.py       # Agent node functions + routing decision
│   ├── tools.py         # Mock log/metric/runbook/history/paging tools
│   └── state.py          # Shared IncidentState schema
├── data/                # Mock logs, metrics, runbooks, incident history
├── static/dashboard.html # Minimal UI to trigger + approve incidents
├── fixtures/sample_incidents.json
├── tests/test_graph.py
├── requirements.txt
├── Dockerfile / docker-compose.yml
└── .env.example
```

## Extending this project

Some natural next steps if you want to build on this further:
- Swap `search_runbooks()` for a real vector store (pgvector, Chroma) —
  the current implementation is naive keyword search on purpose, to keep
  the project runnable with zero infra.
- Add a second escalation channel (real Slack webhook) alongside the mock
  `send_escalation()`.
- Add LangSmith tracing to visualize the graph execution and debug
  confidence/risk decisions across many incidents.
- Add per-service risk policies (e.g. payment services always escalate,
  regardless of confidence) as a config file the router reads.

## License

MIT — use this however is useful for your own portfolio or learning.
