package com.randy.rag.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.randy.rag.model.ChunkSearchResult;
import com.randy.rag.model.QueryRequest;
import com.randy.rag.model.QueryResponse;
import com.randy.rag.model.QueryResponseSource;
import com.randy.rag.service.KnowledgeGraphService.KgCountAnswer;
import com.randy.rag.service.QuestionRouterService.KgRoutedAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final WebClient openAiWebClient;
    private final String chatModel;
    private final QuestionRouterService questionRouterService;
    private final ObjectMapper objectMapper;

    public QueryService(EmbeddingService embeddingService,
                        VectorStoreService vectorStoreService,
                        WebClient openAiWebClient,
                        QuestionRouterService questionRouterService,
                        ObjectMapper objectMapper,
                        @Value("${openai.chat-model}") String chatModel) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.openAiWebClient = openAiWebClient;
        this.chatModel = chatModel;
        this.questionRouterService = questionRouterService;
        this.objectMapper = objectMapper;
    }

    public QueryResponse answer(QueryRequest request) {
        Optional<QueryResponse> kgResponse = tryKnowledgeGraph(request);
        if (kgResponse.isPresent()) {
            log.info("Query routed to knowledge graph path (category={})", request.category());
            return kgResponse.get();
        }

        float[] queryEmbedding = embeddingService.embed(request.query());
        List<ChunkSearchResult> matches = vectorStoreService.searchSimilar(queryEmbedding, request.resolvedTopK(), request.category());
        if (matches.isEmpty()) {
            return new QueryResponse("I could not find relevant information in the knowledge base.", List.of());
        }

        String prompt = buildPrompt(request.query(), matches);
        String answer = invokeChatCompletion(prompt);

        List<QueryResponseSource> sources = matches.stream()
                .map(match -> new QueryResponseSource(match.chunkIndex(), match.similarity()))
                .collect(Collectors.toList());

        return new QueryResponse(answer, sources);
    }

    public Flux<String> answerStream(QueryRequest request) {
        Optional<QueryResponse> kgResponse = tryKnowledgeGraph(request);
        if (kgResponse.isPresent()) {
            return Flux.just(kgResponse.get().answer());
        }

        float[] queryEmbedding = embeddingService.embed(request.query());
        List<ChunkSearchResult> matches = vectorStoreService.searchSimilar(queryEmbedding, request.resolvedTopK(), request.category());
        if (matches.isEmpty()) {
            return Flux.just("I could not find relevant information in the knowledge base.");
        }
        String prompt = buildPrompt(request.query(), matches);
        return streamChatCompletion(prompt);
    }

    private Optional<QueryResponse> tryKnowledgeGraph(QueryRequest request) {
        Optional<KgRoutedAnswer> routed = questionRouterService.routeToKnowledgeGraph(request.query());
        if (routed.isEmpty()) {
            return Optional.empty();
        }
        KgCountAnswer answer = routed.get().answer();
        String prompt = buildKgPrompt(answer, routed.get().scopeLabel());
        String llmAnswer = invokeChatCompletion(prompt);
        return Optional.of(new QueryResponse(llmAnswer, List.of()));
    }

    private String buildPrompt(String question, List<ChunkSearchResult> matches) {
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

    private String buildKgPrompt(KgCountAnswer result, String scopeLabel) {
        return "You are given a structured fact from a knowledge graph.\n"
                + "Character: " + result.characterName() + "\n"
                + "Lost battles " + scopeLabel + ": " + result.count() + "\n"
                + "Explain this to the user in natural language and note that it is based on structured battle data, not long text scanning.";
    }

    private String invokeChatCompletion(String prompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(chatModel, List.of(
                new ChatMessage("system", "You are a meticulous analyst focused on grounded answers."),
                new ChatMessage("user", prompt)
        ), 0.2, false);
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

    private Flux<String> streamChatCompletion(String prompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(chatModel, List.of(
                new ChatMessage("system", "You are a meticulous analyst focused on grounded answers."),
                new ChatMessage("user", prompt)
        ), 0.2, true);

        return openAiWebClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::parseStreamPayload)
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Streaming chat completion failed: {}", e.getResponseBodyAsString());
                    return Flux.error(new EmbeddingService.OpenAiException((HttpStatus) e.getStatusCode(), e.getResponseBodyAsString()));
                });
    }

    private Flux<String> parseStreamPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Flux.empty();
        }
        // Split on newlines to handle multiple SSE events batched in one chunk.
        String[] lines = raw.split("\\r?\\n");
        return Flux.fromArray(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .flatMap(line -> {
                    String payload = line;
                    if (payload.startsWith("data:")) {
                        payload = payload.substring(5).trim();
                    }
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        return Flux.empty();
                    }
                    try {
                        ChatCompletionChunk chunk = objectMapper.readValue(payload, ChatCompletionChunk.class);
                        if (chunk.choices == null || chunk.choices.isEmpty()) {
                            return Flux.empty();
                        }
                        String content = chunk.choices.get(0).delta.content;
                        return content == null ? Flux.empty() : Flux.just(content);
                    } catch (Exception e) {
                        // If it's not JSON, emit raw payload to avoid losing tokens.
                        return Flux.just(payload);
                    }
                });
    }

    private record ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature, boolean stream) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
    }

    private record Choice(ChatMessage message) {
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatCompletionChunk(List<StreamChoice> choices) {
    }

    private record StreamChoice(Delta delta) {
    }

    private record Delta(String content) {
    }
}
