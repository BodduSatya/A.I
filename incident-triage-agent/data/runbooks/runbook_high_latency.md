# Runbook: Elevated Latency (No Errors Yet)

**Symptoms:** `p95_latency_ms` trending upward but `http_5xx_rate` still
near zero. This is an early-warning signal, not yet a hard failure.

**Safe first response (low risk):**
1. Check if the latency correlates with a deploy in the last hour. If so,
   this is informational only — flag it, do not auto-remediate.
2. If no recent deploy, restart the affected service as a low-risk first
   step, since transient latency is often resolved by clearing warm-up
   state or a stuck connection.

**Escalate instead of auto-fixing when:**
- Latency has been elevated for more than 15 minutes with no recovery.
- The service handles payment or auth traffic (blast radius is high).
