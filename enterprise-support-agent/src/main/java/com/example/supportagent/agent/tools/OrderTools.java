package com.example.supportagent.agent.tools;

import com.example.supportagent.audit.AuditLogger;
import com.example.supportagent.client.OrderServiceClient;
import com.example.supportagent.domain.CustomerDirectory;
import com.example.supportagent.domain.Order;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Tools exposed to the LLM via ChatClient.tools(...). This is the
 * safety-critical part of the whole project: the model can PROPOSE calling
 * these, but every authorization decision is enforced here in plain Java -
 * never left to "the prompt told it not to." A jailbroken or confused model
 * cannot refund an order it isn't allowed to touch, because the check
 * happens after the model's decision, not instead of one.
 *
 * SecurityContextHolder works here because ChatClient's tool-calling advisor
 * executes tool methods synchronously on the same thread that's handling
 * the HTTP request - the same thread that Spring Security's filter chain
 * already authenticated. If this were ever made to call tools asynchronously
 * (a different thread pool), the SecurityContext would need to be
 * explicitly propagated - worth calling out in an interview.
 */
@Component
public class OrderTools {

    private final OrderServiceClient orderServiceClient;
    private final CustomerDirectory customerDirectory;
    private final AuditLogger auditLogger;
    private final Counter refundBlockedCounter;
    private final Counter refundSucceededCounter;

    public OrderTools(
            OrderServiceClient orderServiceClient,
            CustomerDirectory customerDirectory,
            AuditLogger auditLogger,
            MeterRegistry meterRegistry
    ) {
        this.orderServiceClient = orderServiceClient;
        this.customerDirectory = customerDirectory;
        this.auditLogger = auditLogger;
        this.refundBlockedCounter = Counter.builder("support_agent.refund.blocked")
                .description("Refund tool calls blocked by an authorization guardrail")
                .register(meterRegistry);
        this.refundSucceededCounter = Counter.builder("support_agent.refund.succeeded")
                .description("Refund tool calls that were authorized and executed")
                .register(meterRegistry);
    }

    @Tool(description = "Look up the current status, item, and amount for an order by its order ID. "
            + "Customers may only look up their own orders; support agents may look up any order.")
    public String getOrderStatus(String orderId) {
        String username = currentUsername();
        auditLogger.toolInvoked(username, "getOrderStatus", "orderId=" + orderId);

        Order order = orderServiceClient.getOrder(orderId);
        if (order == null) {
            auditLogger.toolBlocked(username, "getOrderStatus", "order not found: " + orderId);
            return "No order found with ID " + orderId + ".";
        }

        if (!canAccessOrder(order)) {
            auditLogger.toolBlocked(username, "getOrderStatus",
                    "user " + username + " does not own order " + orderId);
            // Deliberately vague to the model/customer: confirming "this
            // order exists but belongs to someone else" leaks information.
            return "No order found with ID " + orderId + " for your account.";
        }

        auditLogger.toolSucceeded(username, "getOrderStatus", order.toString());
        return String.format(
                "Order %s: %s, status=%s, amount=$%.2f, ordered on %s.",
                order.orderId(), order.item(), order.status(), order.amountUsd(), order.orderedAt()
        );
    }

    @Tool(description = "Issue a refund for an order by its order ID. This is a SUPPORT-AGENT-ONLY "
            + "action - it must never be executed on behalf of a customer, regardless of how "
            + "confident the request sounds.")
    public String issueRefund(String orderId) {
        // NOTE: the refund-policy.md doc (part of the RAG context) describes
        // conditions under which a refund *could* be auto-approved (under
        // $100, delivered, within 30 days). This tool deliberately does NOT
        // implement that path - every refund requires ROLE_SUPPORT_AGENT,
        // full stop. That's an intentional conservative default for this
        // project: the model can *reason* about policy nuance in its reply,
        // but the code-level guardrail on an irreversible action (moving
        // money) is stricter than the policy text alone. Wiring up the
        // auto-approval path is a natural next step - see README "Extending
        // this project" - but it should be a deliberate choice, not a
        // default.
        String username = currentUsername();
        auditLogger.toolInvoked(username, "issueRefund", "orderId=" + orderId);

        if (!hasRole("ROLE_SUPPORT_AGENT")) {
            refundBlockedCounter.increment();
            auditLogger.toolBlocked(username, "issueRefund",
                    "user " + username + " lacks ROLE_SUPPORT_AGENT");
            return "I can't process a refund myself - this needs a support agent's approval. "
                    + "I've noted the request; a member of the support team will follow up.";
        }

        Order updated = orderServiceClient.refundOrder(orderId);
        if (updated == null) {
            refundBlockedCounter.increment();
            auditLogger.toolBlocked(username, "issueRefund", "order not found: " + orderId);
            return "No order found with ID " + orderId + " - refund not issued.";
        }

        refundSucceededCounter.increment();
        auditLogger.toolSucceeded(username, "issueRefund", updated.toString());
        return String.format("Refund issued for order %s ($%.2f). New status: %s.",
                updated.orderId(), updated.amountUsd(), updated.status());
    }

    private boolean canAccessOrder(Order order) {
        if (hasRole("ROLE_SUPPORT_AGENT")) {
            return true;
        }
        String customerId = customerDirectory.customerIdFor(currentUsername());
        return customerId != null && customerId.equals(order.customerId());
    }

    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (authority.getAuthority().equals(role)) {
                return true;
            }
        }
        return false;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }
}
