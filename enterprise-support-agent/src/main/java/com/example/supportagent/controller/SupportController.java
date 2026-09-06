package com.example.supportagent.controller;

import com.example.supportagent.domain.ChatRequest;
import com.example.supportagent.domain.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final ChatClient chatClient;

    public SupportController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Single-turn support chat endpoint. The authenticated principal (set
     * by Spring Security from HTTP Basic auth) is what OrderTools reads via
     * SecurityContextHolder to decide what this user is allowed to do - the
     * request body never carries a "who am I" field the client could spoof.
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        String reply = chatClient.prompt()
                .user(request.message())
                .call()
                .content();

        return new ChatResponse(conversationId, reply);
    }
}
