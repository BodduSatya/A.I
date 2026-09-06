# Enterprise Support Agent (Spring AI)

An agentic customer-support system built on **Spring Boot + Spring AI**,
demonstrating what "agentic AI in an enterprise stack" actually looks like:
RAG over real policy docs, tool-calling into a downstream service,
role-based access control enforced in code (not just prompted), a
service-to-service auth pattern, and Actuator-backed observability.

This is the companion Java project to a Python/LangGraph incident-triage
agent — built to show the same agentic-AI competency inside the kind of
stack a Java/Spring backend team actually runs in production.

> **A note on how this was built:** every file here was written carefully
> against Spring AI's confirmed 1.0.0 API (artifact names, config
> properties, `ChatClient`/`@Tool`/`QuestionAnswerAdvisor` usage), but I was
> not able to run `mvn compile` or `mvn test` in the environment that
> produced this project — no access to Maven Central. Treat this as a
> carefully-written, logically-reviewed starting point rather than something
> proven to build with zero edits. Run `mvn clean install` first thing; if
> anything doesn't compile, it's most likely a minor Spring AI API drift
> (this ecosystem moves fast) rather than a structural problem — the
> architecture and guardrail logic are the parts worth your review time.

---

## Why this project exists

Most agentic AI portfolios are pure Python. This one exists to answer the
question an enterprise Java interviewer will actually ask: *"can you put
this into the kind of system we run?"* — with real RBAC, a real
service-boundary, and real observability, not just an LLM wrapped in a
`@RestController`.

## Architecture

```
                     POST /api/support/chat
                     (HTTP Basic auth: alice / bob / agent1)
                              │
                              ▼
                  ┌───────────────────────┐
                  │   SupportController     │
                  └───────────┬────────────┘
                              │
                              ▼
                  ┌───────────────────────┐
                  │      ChatClient         │  Spring AI, Claude model
                  │  ┌─────────────────┐   │
                  │  │ QuestionAnswer-  │   │──▶ VectorStore (RAG over
                  │  │ Advisor (RAG)    │   │     shipping/refund/FAQ docs)
                  │  └─────────────────┘   │
                  │  ┌─────────────────┐   │
                  │  │ OrderTools       │   │──▶ RBAC check (in code) ──▶ OrderServiceClient
                  │  │ (@Tool methods)  │   │                              (service-to-service
                  │  └─────────────────┘   │                               auth, own credential)
                  └───────────────────────┘                                       │
                                                                                    ▼
                                                                     MockOrderController
                                                                     (stands in for a real,
                                                                      separately-deployed
                                                                      order-management service)
```

### The part that actually matters: guardrails enforced in code

The whole point of this project is the boundary between "the model decides
to call a tool" and "the tool actually executes." Those are not the same
thing, and conflating them is how agentic systems get exploited or just get
things wrong.

- **`OrderTools.getOrderStatus`** — a customer can only look up their *own*
  order. This is checked against `CustomerDirectory` in Java, not by
  hoping the model respects "only show the user their own orders" in the
  system prompt. If it doesn't match, the tool returns a deliberately vague
  "not found" — confirming an order exists but belongs to someone else
  would itself be a data leak.
- **`OrderTools.issueRefund`** — requires `ROLE_SUPPORT_AGENT`, full stop,
  checked via `SecurityContextHolder` inside the tool method itself. A
  customer asking for a refund gets a tool response saying it's been
  noted for a human, and the system prompt explicitly instructs the model
  not to imply the refund happened — because the *tool*, not the prompt, is
  the actual source of truth about what occurred.
- **Service-to-service auth is separate from user auth.** The app calls the
  downstream order service with its own dedicated credential
  (`support-agent-service`), not the logged-in customer's session. Per-user
  authorization happens in `OrderTools` *before* that call is made. This
  mirrors how a real microservice boundary works — the downstream service
  trusts the calling service, and the calling service is responsible for
  having already checked whether this specific user is allowed to trigger
  this specific call.
- **Refunds are conservative on purpose.** `refund-policy.md` (part of the
  RAG context) describes conditions under which a refund could
  theoretically be auto-approved. The `issueRefund` tool deliberately does
  **not** implement that — every refund needs a human. That gap is
  intentional: an LLM reasoning about a policy document is not the same
  guarantee as a hard-coded rule, and this project treats "moves money" as
  a line worth being stricter than the doc.

### Observability

- Every tool call — invoked, blocked, or succeeded — goes through
  `AuditLogger` as structured log lines (`SUPPORT_AGENT_AUDIT` logger),
  ready to ship to a real log aggregator.
- `support_agent.refund.blocked` and `support_agent.refund.succeeded`
  Micrometer counters are exposed via `/actuator/prometheus`, so you can
  graph "how often is the guardrail actually firing" — the kind of metric
  that matters once this is running for real.
- `/actuator/health` is public; the rest of `/actuator/**` requires
  `ROLE_SUPPORT_AGENT`.

---

## Setup

### Prerequisites
- Java 17+
- Maven 3.9+
- An Anthropic API key ([console.anthropic.com](https://console.anthropic.com))

### 1. Clone and configure

```bash
git clone <your-repo-url>
cd enterprise-support-agent
cp .env.example .env
```

Edit `.env` and set `ANTHROPIC_API_KEY`.

### 2. Build

```bash
mvn clean install
```

The first build will download an ONNX embedding model
(`sentence-transformers/all-MiniLM-L6-v2`) used for local, free RAG
embeddings — no OpenAI key needed just to embed documents. This means the
first run may take a little longer while it caches the model.

### 3. Run

```bash
export ANTHROPIC_API_KEY=your-key-here
mvn spring-boot:run
```

The app starts on **http://localhost:8080** and ingests the docs under
`src/main/resources/docs/` into the in-memory vector store on startup —
check the logs for `Ingested N document chunks...`.

### 4. Run with Docker

```bash
docker compose up --build
```

(Requires `ANTHROPIC_API_KEY` to be set in your shell or a `.env` file next
to `docker-compose.yml`.)

### 5. Run the tests

```bash
mvn test
```

`OrderToolsTests` exercises the RBAC guardrail logic directly with mocked
dependencies — no API key, no Spring context, no network calls needed.
This is deliberately the most heavily-tested part of the project, since
it's the part where a bug would actually matter.

---

## Trying it out

Three seeded users (see `SecurityConfig` for details):

| Username | Password | Role | Notes |
|---|---|---|---|
| `alice` | `password` | CUSTOMER | owns orders `ORD-5001`, `ORD-5002` |
| `bob` | `password` | CUSTOMER | owns orders `ORD-5003`, `ORD-5004` |
| `agent1` | `password` | SUPPORT_AGENT | can view/refund any order |

### Example: a customer checks their own order

```bash
curl -u alice:password -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is the status of order ORD-5001?"}'
```

### Example: a customer tries to look up someone else's order

```bash
curl -u alice:password -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is the status of order ORD-5003?"}'
```

Expect a "no order found for your account" style response — `ORD-5003`
belongs to bob, not alice — even though the order genuinely exists.

### Example: a customer asks for a refund (gets escalated, not executed)

```bash
curl -u alice:password -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Please refund my order ORD-5001, it arrived damaged."}'
```

### Example: a support agent issues the same refund

```bash
curl -u agent1:password -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Please refund order ORD-5001."}'
```

### Example: ask a policy question (exercises RAG)

```bash
curl -u alice:password -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "How long does standard shipping take, and is it free?"}'
```

---

## Project structure

```
enterprise-support-agent/
├── pom.xml
├── docker-compose.yml / Dockerfile
├── .env.example
└── src/
    ├── main/java/com/example/supportagent/
    │   ├── SupportAgentApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java       # RBAC users, service account, filter chain
    │   │   ├── VectorStoreConfig.java    # RAG vector store + doc ingestion on startup
    │   │   └── ChatClientConfig.java     # ChatClient wiring: system prompt, advisors, tools
    │   ├── controller/
    │   │   └── SupportController.java    # POST /api/support/chat
    │   ├── agent/tools/
    │   │   └── OrderTools.java           # @Tool methods + RBAC enforcement (the core of the project)
    │   ├── client/
    │   │   └── OrderServiceClient.java   # HTTP client, service-to-service auth
    │   ├── domain/
    │   │   ├── Order.java, ChatRequest.java, ChatResponse.java
    │   │   └── CustomerDirectory.java    # username -> customerId mapping
    │   ├── mock/
    │   │   ├── MockOrderController.java  # stands in for a real order-management service
    │   │   └── OrderRepository.java      # in-memory seeded order data
    │   └── audit/
    │       └── AuditLogger.java          # structured audit logging for every tool call
    ├── main/resources/
    │   ├── application.yml
    │   └── docs/                         # RAG knowledge base
    │       ├── shipping-policy.md
    │       ├── refund-policy.md
    │       └── account-faq.md
    └── test/java/.../OrderToolsTests.java
```

## Extending this project

- **Real vector store:** swap `SimpleVectorStore` for `PgVectorStore`
  (`spring-ai-starter-vector-store-pgvector`) — nothing else changes, since
  the app is coded against the `VectorStore` interface throughout.
- **Split the order service out for real:** `MockOrderController` +
  `OrderRepository` are intentionally isolated in `mock/` so they're easy
  to delete once you point `OrderServiceClient` at an actually-deployed
  service.
- **Real identity provider:** swap `InMemoryUserDetailsManager` for OAuth2
  resource server config backed by your IdP — the RBAC checks in
  `OrderTools` don't need to change, since they work off
  `SecurityContextHolder` regardless of how authentication happened.
- **Wire up the conditional auto-refund path** described in
  `refund-policy.md` but deliberately not implemented in `OrderTools` — a
  good exercise in translating a written policy into code with the same
  kind of explicit, testable conditions used in `OrderToolsTests`.
- **Multi-turn memory:** add `MessageChatMemoryAdvisor` to `ChatClientConfig`
  so `conversationId` actually threads a real conversation instead of each
  request being single-turn.

## License

MIT — use this however is useful for your own portfolio or learning.
