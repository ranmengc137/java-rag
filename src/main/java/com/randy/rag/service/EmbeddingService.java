package com.randy.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient openAiWebClient;
    private final String embeddingModel;

    public EmbeddingService(WebClient openAiWebClient,
                            @Value("${openai.embedding-model}") String embeddingModel) {
        this.openAiWebClient = openAiWebClient;
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }

        // Use OpenAI's embedding endpoint to map the chunk into vector space.
        // We send one chunk per request to simplify retry/error handling.
        EmbeddingRequest request = new EmbeddingRequest(embeddingModel, List.of(text));

        try {
            EmbeddingResponse response = openAiWebClient.post()
                    .uri("/embeddings")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new OpenAiException((HttpStatus) ex.getStatusCode(), ex.getResponseBodyAsString())))
                    .block();

            if (response == null || response.data == null || response.data.isEmpty()) {
                // We treat a missing embedding as a hard failure because retrieval would silently degrade.
                throw new OpenAiException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI returned empty embedding data");
            }

            List<Double> embedding = response.data.get(0).embedding();
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).floatValue();
            }
            return vector;
        } catch (OpenAiException e) {
            log.error("OpenAI embedding request failed: {}", e.getMessage());
            throw e;
        }
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    private record EmbeddingData(String object, List<Double> embedding) {
    }

    public static class OpenAiException extends RuntimeException {
        private final HttpStatus status;

        public OpenAiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }
}
