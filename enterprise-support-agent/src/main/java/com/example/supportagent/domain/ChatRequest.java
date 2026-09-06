package com.example.supportagent.domain;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        String message,

        // Client-generated id to keep a multi-turn conversation grouped.
        // Optional - a new one is generated if omitted.
        String conversationId
) {
}
