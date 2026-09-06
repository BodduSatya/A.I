package com.example.supportagent.mock;

import com.example.supportagent.domain.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "database" for the mock order-management service. Swap this for
 * a real repository/JPA entity + Postgres when this is split into an actual
 * separate microservice - MockOrderController's HTTP contract wouldn't need
 * to change.
 */
@Component
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public OrderRepository() {
        seed(new Order("ORD-5001", "CUST-1001", "Wireless Keyboard", "SHIPPED", 49.99, "2026-08-01"));
        seed(new Order("ORD-5002", "CUST-1001", "USB-C Hub", "DELIVERED", 29.99, "2026-07-20"));
        seed(new Order("ORD-5003", "CUST-1002", "27in Monitor", "PROCESSING", 249.99, "2026-08-06"));
        seed(new Order("ORD-5004", "CUST-1002", "Laptop Stand", "DELAYED", 39.99, "2026-08-03"));
    }

    private void seed(Order order) {
        orders.put(order.orderId(), order);
    }

    public Order findById(String orderId) {
        return orders.get(orderId);
    }

    public Order updateStatus(String orderId, String newStatus) {
        Order existing = orders.get(orderId);
        if (existing == null) {
            return null;
        }
        Order updated = new Order(
                existing.orderId(), existing.customerId(), existing.item(),
                newStatus, existing.amountUsd(), existing.orderedAt()
        );
        orders.put(orderId, updated);
        return updated;
    }
}
