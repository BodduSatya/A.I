package com.example.supportagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires an in-memory VectorStore (SimpleVectorStore) and, on startup, loads
 * every markdown file under src/main/resources/docs/ into it. This is the
 * RAG knowledge base the support agent draws on via QuestionAnswerAdvisor.
 *
 * SimpleVectorStore keeps this project runnable with zero external
 * infrastructure. For a real deployment, swap this bean for PgVectorStore
 * (spring-ai-starter-vector-store-pgvector) backed by Postgres - the rest of
 * the app (ChatClientConfig, controllers, tools) doesn't need to change,
 * since everything is coded against the VectorStore interface.
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public ApplicationRunner ingestSupportDocs(VectorStore vectorStore) {
        return (ApplicationArguments args) -> {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] docResources;
            try {
                docResources = resolver.getResources("classpath:docs/*.md");
            } catch (IOException e) {
                log.warn("No docs/ resources found to ingest into the vector store: {}", e.getMessage());
                return;
            }

            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> allChunks = new ArrayList<>();

            for (Resource resource : docResources) {
                TextReader reader = new TextReader(resource);
                reader.getCustomMetadata().put("source", resource.getFilename());
                List<Document> docs = reader.get();
                allChunks.addAll(splitter.apply(docs));
            }

            if (!allChunks.isEmpty()) {
                vectorStore.add(allChunks);
                log.info("Ingested {} document chunks from {} files into the vector store.",
                        allChunks.size(), docResources.length);
            }
        };
    }
}
