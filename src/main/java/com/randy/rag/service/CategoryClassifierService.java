package com.randy.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Service
public class CategoryClassifierService {

    private static final Logger log = LoggerFactory.getLogger(CategoryClassifierService.class);

    private final WebClient openAiWebClient;
    private final String chatModel;
    private final String labelList;

    public CategoryClassifierService(WebClient openAiWebClient,
                                     @Value("${openai.chat-model}") String chatModel,
                                     @Value("${category.labels:HISTORY,CHILDCARE,LEGAL,TECHNOLOGY,FINANCE,SCIENCE,HEALTH,LITERATURE,FICTION,BUSINESS,EDUCATION,OTHER}") String labelList) {
        this.openAiWebClient = openAiWebClient;
        this.chatModel = chatModel;
        this.labelList = labelList;
    }

    public String classify(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String sample = text.length() > 2000 ? text.substring(0, 2000) : text;
        ChatCompletionRequest request = new ChatCompletionRequest(chatModel, java.util.List.of(
                new ChatMessage("system", "Classify the document into one of: " + labelList + ". Reply with the single label only."),
                new ChatMessage("user", sample)
        ));
        try {
            ChatCompletionResponse response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ChatCompletionResponse.class)
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new EmbeddingService.OpenAiException((HttpStatus) ex.getStatusCode(), ex.getResponseBodyAsString())))
                    .block();
            if (response == null || response.choices == null || response.choices.isEmpty()) {
                return fallback;
            }
            String label = response.choices.get(0).message.content;
            String result = normalize(label, fallback);
            log.info("Auto-classified document as {} from model label '{}'", result, label);
            return result;
        } catch (Exception e) {
            log.warn("Category classification failed: {}", e.getMessage());
            return fallback;
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.trim().toLowerCase();
        // Optionally restrict to provided labels; otherwise accept as-is.
        String[] labels = labelList.split(",");
        for (String label : labels) {
            if (cleaned.equalsIgnoreCase(label.trim())) {
                return label.trim().toLowerCase();
            }
        }
        return cleaned;
    }

    private record ChatCompletionRequest(String model, java.util.List<ChatMessage> messages) {
    }

    private record ChatCompletionResponse(java.util.List<Choice> choices) {
    }

    private record Choice(ChatMessage message) {
    }

    private record ChatMessage(String role, String content) {
    }
}
