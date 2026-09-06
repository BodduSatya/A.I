package com.example.supportagent.mock;

import com.example.supportagent.domain.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stands in for a separate order-management microservice. In a real
 * enterprise setup this would be its own deployed Spring Boot service (its
 * own repo, its own datastore) reached over the network from
 * OrderServiceClient. It's mounted in this same app purely to keep the demo
 * a single `docker compose up` with no extra services to stand up.
 *
 * Locked to ROLE_SUPPORT_AGENT at the security-filter level (see
 * SecurityConfig) since nothing outside this app should call it directly -
 * the support agent talks to it through OrderServiceClient -> OrderTools,
 * which run inside the already-authenticated request.
 */
@RestController
@RequestMapping("/internal/orders")
public class MockOrderController {

    private final OrderRepository repository;

    public MockOrderController(OrderRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Order order = repository.findById(orderId);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<Order> refundOrder(@PathVariable String orderId) {
        Order updated = repository.updateStatus(orderId, "REFUNDED");
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }
}
