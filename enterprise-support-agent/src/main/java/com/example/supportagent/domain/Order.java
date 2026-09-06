package com.example.supportagent.domain;

/**
 * Mirrors the shape returned by the (mock) order-management service.
 */
public record Order(
        String orderId,
        String customerId,
        String item,
        String status,
        double amountUsd,
        String orderedAt
) {
}
