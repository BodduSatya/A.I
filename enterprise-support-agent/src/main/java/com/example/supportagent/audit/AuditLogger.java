package com.example.supportagent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every sensitive tool call - and every time a guardrail blocks one - goes
 * through here. In production this would ship to your log aggregator
 * (ELK/Datadog/etc.) with the same structured fields; kept as SLF4J only so
 * the project has zero extra infra to run.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("SUPPORT_AGENT_AUDIT");

    public void toolInvoked(String username, String tool, String argsSummary) {
        log.info("tool_invoked user={} tool={} args=[{}]", username, tool, argsSummary);
    }

    public void toolBlocked(String username, String tool, String reason) {
        log.warn("tool_blocked user={} tool={} reason=\"{}\"", username, tool, reason);
    }

    public void toolSucceeded(String username, String tool, String resultSummary) {
        log.info("tool_succeeded user={} tool={} result=[{}]", username, tool, resultSummary);
    }
}
