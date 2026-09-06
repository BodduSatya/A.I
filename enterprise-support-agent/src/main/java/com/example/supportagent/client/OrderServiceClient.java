package com.example.supportagent.client;

import com.example.supportagent.domain.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Talks to the (mock) order-management service over HTTP using this
 * application's own service identity - see SecurityConfig for why this is
 * NOT the end-user's credentials. In a real deployment, base-url would
 * point at the actual deployed service (or go through service discovery),
 * and the credential would come from a secret manager, not application.yml.
 */
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(
            @Value("${support-agent.order-service.base-url}") String baseUrl,
            @Value("${support-agent.order-service.username}") String username,
            @Value("${support-agent.order-service.password}") String password
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(username, password);
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Returns the order, or null if it doesn't exist. */
    public Order getOrder(String orderId) {
        try {
            return restClient.get()
                    .uri("/{orderId}", orderId)
                    .retrieve()
                    .body(Order.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Issues a refund for the order, returning the updated order, or null if it doesn't exist. */
    public Order refundOrder(String orderId) {
        try {
            return restClient.post()
                    .uri("/{orderId}/refund", orderId)
                    .retrieve()
                    .body(Order.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
