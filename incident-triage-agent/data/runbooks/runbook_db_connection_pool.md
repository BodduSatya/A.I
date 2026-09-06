# Runbook: DB Connection Pool Exhaustion

**Symptoms:** rising `db_connection_pool_utilization_pct`, timeout errors acquiring
a connection, elevated `http_5xx_rate` and `p95_latency_ms`.

**Safe first response (low risk):**
1. Restart the affected service's connection pool / process.
2. If utilization stays above 90% after restart, temporarily raise the max
   pool size by 25%.

**Escalate instead of auto-fixing when:**
- The spike correlates with a downstream dependency (e.g. payment provider)
  being slow — restarting the pool will not fix a slow downstream call, and
  repeatedly restarting can mask the real problem.
- This is the second occurrence in 24 hours for the same service.

**Do not** modify database schema or connection credentials as part of this
runbook — that requires a human with DB access.
