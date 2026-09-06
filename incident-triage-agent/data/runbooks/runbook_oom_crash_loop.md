# Runbook: OOM Crash Loop

**Symptoms:** `memory_usage_pct` climbing toward 100%, repeated
`OutOfMemoryError`, `pod_restarts_last_hour` increasing.

**This is a MEDIUM/HIGH risk situation, not a safe auto-fix.** A pod that
keeps OOM-killing itself usually indicates a memory leak or an unbounded
cache, not a transient blip. Restarting the pod provides only temporary
relief and can hide the real issue.

**Recommended response:**
1. Escalate to a human immediately if this is a recurring pattern (2+
   restarts within the hour).
2. Human should check recent deploys — if a new image was shipped in the
   last 24h, a rollback is often the fastest safe fix.
3. Do not scale replicas as a substitute fix; it can multiply the memory
   pressure across more pods rather than resolving it.
