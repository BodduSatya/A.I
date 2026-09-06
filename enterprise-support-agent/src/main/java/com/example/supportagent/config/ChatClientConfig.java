package com.example.supportagent.config;

import com.example.supportagent.agent.tools.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a customer support agent for an e-commerce company.

            You can look up order status and, if the requester is a support
            agent, issue refunds - always by calling the appropriate tool,
            never by claiming you did something you didn't.

            Ground your answers about policies (shipping, refunds, accounts)
            in the retrieved documentation context provided to you. If the
            context doesn't cover something, say so plainly instead of
            guessing.

            If a customer asks for a refund, use the issueRefund tool. If it
            reports back that this requires a support agent, tell the
            customer clearly that their request has been noted and a support
            agent will follow up - do not imply the refund happened.

            Keep responses concise and professional.
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore, OrderTools orderTools) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore).build(),
                        new SimpleLoggerAdvisor()
                )
                .defaultTools(orderTools)
                .build();
    }
}
