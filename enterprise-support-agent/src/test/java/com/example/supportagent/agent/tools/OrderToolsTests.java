package com.example.supportagent.agent.tools;

import com.example.supportagent.audit.AuditLogger;
import com.example.supportagent.client.OrderServiceClient;
import com.example.supportagent.domain.CustomerDirectory;
import com.example.supportagent.domain.Order;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * These tests exercise the guardrail logic directly - the part of this
 * project where a bug would actually matter (a customer refunding an order
 * they don't own, or seeing someone else's order status). They do not spin
 * up Spring context or call a real LLM, by design: fast, deterministic,
 * no API key required.
 */
class OrderToolsTests {

    private final OrderServiceClient orderServiceClient = mock(OrderServiceClient.class);
    private final CustomerDirectory customerDirectory = mock(CustomerDirectory.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);

    private final OrderTools orderTools = new OrderTools(
            orderServiceClient, customerDirectory, auditLogger, new SimpleMeterRegistry()
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(username, "n/a", authorities)
        );
    }

    @Test
    void customerCanViewTheirOwnOrder() {
        loginAs("alice", "CUSTOMER");
        when(customerDirectory.customerIdFor("alice")).thenReturn("CUST-1001");
        when(orderServiceClient.getOrder("ORD-5001"))
                .thenReturn(new Order("ORD-5001", "CUST-1001", "Keyboard", "SHIPPED", 49.99, "2026-08-01"));

        String result = orderTools.getOrderStatus("ORD-5001");

        assertThat(result).contains("ORD-5001").contains("SHIPPED");
    }

    @Test
    void customerCannotViewSomeoneElsesOrder() {
        loginAs("alice", "CUSTOMER");
        when(customerDirectory.customerIdFor("alice")).thenReturn("CUST-1001");
        // ORD-5003 belongs to CUST-1002 (bob), not alice
        when(orderServiceClient.getOrder("ORD-5003"))
                .thenReturn(new Order("ORD-5003", "CUST-1002", "Monitor", "PROCESSING", 249.99, "2026-08-06"));

        String result = orderTools.getOrderStatus("ORD-5003");

        assertThat(result).doesNotContain("PROCESSING").contains("No order found");
        verify(auditLogger).toolBlocked(eq("alice"), eq("getOrderStatus"), anyString());
    }

    @Test
    void supportAgentCanViewAnyOrder() {
        loginAs("agent1", "SUPPORT_AGENT");
        when(orderServiceClient.getOrder("ORD-5003"))
                .thenReturn(new Order("ORD-5003", "CUST-1002", "Monitor", "PROCESSING", 249.99, "2026-08-06"));

        String result = orderTools.getOrderStatus("ORD-5003");

        assertThat(result).contains("ORD-5003").contains("PROCESSING");
    }

    @Test
    void customerCannotIssueARefund() {
        loginAs("alice", "CUSTOMER");

        String result = orderTools.issueRefund("ORD-5001");

        assertThat(result).contains("support agent");
        verify(orderServiceClient, never()).refundOrder(anyString());
        verify(auditLogger).toolBlocked(eq("alice"), eq("issueRefund"), anyString());
    }

    @Test
    void supportAgentCanIssueARefund() {
        loginAs("agent1", "SUPPORT_AGENT");
        when(orderServiceClient.refundOrder("ORD-5001"))
                .thenReturn(new Order("ORD-5001", "CUST-1001", "Keyboard", "REFUNDED", 49.99, "2026-08-01"));

        String result = orderTools.issueRefund("ORD-5001");

        assertThat(result).contains("Refund issued").contains("REFUNDED");
        verify(orderServiceClient).refundOrder("ORD-5001");
    }

    @Test
    void refundOnNonexistentOrderIsHandledGracefully() {
        loginAs("agent1", "SUPPORT_AGENT");
        when(orderServiceClient.refundOrder("ORD-9999")).thenReturn(null);

        String result = orderTools.issueRefund("ORD-9999");

        assertThat(result).contains("No order found");
    }
}
