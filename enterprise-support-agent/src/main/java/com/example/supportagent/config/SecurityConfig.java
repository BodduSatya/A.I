package com.example.supportagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo-grade authentication: in-memory users with HTTP Basic auth, purely so
 * this project is runnable with zero external identity infra.
 *
 * In a real deployment, swap InMemoryUserDetailsManager for OAuth2/OIDC
 * (spring-boot-starter-oauth2-resource-server) backed by your identity
 * provider, and keep the @PreAuthorize / role-check pattern used in
 * OrderTools and SupportController - that part of the design doesn't change.
 *
 * Seeded users (see CustomerDirectory for the customerId mapping used by
 * OrderTools to enforce "customers can only see their own orders"):
 *   alice / password   -> ROLE_CUSTOMER      (customerId CUST-1001)
 *   bob   / password   -> ROLE_CUSTOMER      (customerId CUST-1002)
 *   agent1/ password   -> ROLE_SUPPORT_AGENT (can view/refund any order)
 *
 * A fifth account, "support-agent-service", represents this application's
 * own service identity when it calls the downstream (mock) order-management
 * service. This mirrors a real deployment: the order service trusts the
 * calling service via a dedicated service credential, NOT the end-user's
 * session - per-user authorization (can this customer see this order?) is
 * enforced in application code in OrderTools, before the downstream call is
 * ever made. See OrderTools for that check.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails alice = User.withUsername("alice")
                .password(encoder.encode("password"))
                .roles("CUSTOMER")
                .build();

        UserDetails bob = User.withUsername("bob")
                .password(encoder.encode("password"))
                .roles("CUSTOMER")
                .build();

        UserDetails agent1 = User.withUsername("agent1")
                .password(encoder.encode("password"))
                .roles("SUPPORT_AGENT")
                .build();

        UserDetails serviceAccount = User.withUsername("support-agent-service")
                .password(encoder.encode("service-secret"))
                .roles("INTERNAL_SERVICE")
                .build();

        return new InMemoryUserDetailsManager(alice, bob, agent1, serviceAccount);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless API, no cookies/forms involved
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("SUPPORT_AGENT")
                .requestMatchers("/api/**").authenticated()
                // Downstream order-management service: only this
                // application's own service identity may call it directly.
                // A customer's session is never used to reach it - see the
                // class-level Javadoc above.
                .requestMatchers("/internal/**").hasRole("INTERNAL_SERVICE")
                .anyRequest().denyAll()
            )
            .httpBasic(basic -> {});

        return http.build();
    }
}
