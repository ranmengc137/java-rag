package com.randy.rag.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.randy.rag.model.ChunkSearchResult;
import com.randy.rag.model.QueryRequest;
import com.randy.rag.model.QueryResponse;
import com.randy.rag.model.QueryResponseSource;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final WebClient openAiWebClient;
    private final String chatModel;

    public QueryService(EmbeddingService embeddingService,
                        VectorStoreService vectorStoreService,
                        WebClient openAiWebClient,
                        @Value("${openai.chat-model}") String chatModel) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.openAiWebClient = openAiWebClient;
        this.chatModel = chatModel;
    }

    public QueryResponse answer(QueryRequest request) {
        // Step 1: Vectorize the user query so we can compare it against stored chunks.
        float[] queryEmbedding = embeddingService.embed(request.query());
        // Step 2: Retrieve the most relevant knowledge snippets based on vector similarity.
        List<ChunkSearchResult> matches = vectorStoreService.searchSimilar(queryEmbedding, request.resolvedTopK());
        if (matches.isEmpty()) {
            return new QueryResponse("I could not find relevant information in the knowledge base.", List.of());
        }

        // Step 3: Build a grounded prompt from retrieved chunks and send it to the LLM.
        String prompt = buildPrompt(request.query(), matches);
        String answer = invokeChatCompletion(prompt);

        List<QueryResponseSource> sources = matches.stream()
                .map(match -> new QueryResponseSource(match.chunkIndex(), match.similarity()))
                .collect(Collectors.toList());

        return new QueryResponse(answer, sources);
    }

    private String buildPrompt(String question, List<ChunkSearchResult> matches) {
        // Force the LLM to stay grounded by enumerating every retrieved chunk inline.
        // Any hallucinations can be traced back to specific chunk indices returned in the response.
        StringBuilder builder = new StringBuilder("Use ONLY the following retrieved information to answer the user's question.\n\n");
        matches.forEach(match -> builder.append("[chunk ")
                .append(match.chunkIndex())
                .append("] ")
                .append(match.content())
                .append("\n\n"));
        builder.append("Question: ").append(question).append("\n")
                .append("Answer in a concise paragraph and cite the supporting chunk index in brackets, e.g., [chunk 0].");
        return builder.toString();
    }

    private String invokeChatCompletion(String prompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(chatModel, List.of(
                new ChatMessage("system", "You are a meticulous analyst focused on grounded answers."),
                new ChatMessage("user", prompt)
        ), 0.2);
        try {
            ChatCompletionResponse response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ChatCompletionResponse.class)
                    .block();
            if (response == null || response.choices == null || response.choices.isEmpty()) {
                throw new EmbeddingService.OpenAiException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI returned empty completion");
            }
            return response.choices.get(0).message.content;
        } catch (WebClientResponseException e) {
            log.error("Chat completion failed: {}", e.getResponseBodyAsString());
            throw new EmbeddingService.OpenAiException((HttpStatus) e.getStatusCode(), e.getResponseBodyAsString());
        }
    }

    private record ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
    }

    private record Choice(ChatMessage message) {
    }

    private record ChatMessage(String role, String content) {
    }
}
