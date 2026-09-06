package com.example.supportagent.domain;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * In a real system this mapping would come from your customer/identity
 * database, not a hardcoded map. Kept here to keep the demo runnable
 * without a database, while still exercising real ownership checks in
 * OrderTools (a customer cannot fetch or refund another customer's order).
 */
@Component
public class CustomerDirectory {

    private static final Map<String, String> USERNAME_TO_CUSTOMER_ID = Map.of(
            "alice", "CUST-1001",
            "bob", "CUST-1002"
    );

    /** Returns the customerId for a given username, or null if not a customer account. */
    public String customerIdFor(String username) {
        return USERNAME_TO_CUSTOMER_ID.get(username);
    }
}
