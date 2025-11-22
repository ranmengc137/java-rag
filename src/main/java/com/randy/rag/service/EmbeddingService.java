package com.randy.rag.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient openAiWebClient;
    private final String embeddingModel;
    private final int batchSize;
    private final MeterRegistry meterRegistry;
    private final Timer embeddingTimer;
    private final Counter embeddingErrors;

    public EmbeddingService(WebClient openAiWebClient,
                            @Value("${openai.embedding-model}") String embeddingModel,
                            @Value("${openai.embedding.batch-size:16}") int batchSize,
                            MeterRegistry meterRegistry) {
        this.openAiWebClient = openAiWebClient;
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.meterRegistry = meterRegistry;
        this.embeddingTimer = meterRegistry.timer("rag.embedding.duration");
        this.embeddingErrors = meterRegistry.counter("rag.embedding.errors");
    }

    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        List<String> pendingBatch = new ArrayList<>(batchSize);
        List<Integer> pendingIndexes = new ArrayList<>(batchSize);

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                results.set(i, new float[0]);
                continue;
            }
            pendingBatch.add(text);
            pendingIndexes.add(i);
            if (pendingBatch.size() == batchSize) {
                log.debug("Embedding batch of size {}", batchSize);
                assignBatch(results, pendingBatch, pendingIndexes);
                pendingBatch.clear();
                pendingIndexes.clear();
            }
        }

        if (!pendingBatch.isEmpty()) {
            assignBatch(results, pendingBatch, pendingIndexes);
        }

        return results;
    }

    private void assignBatch(List<float[]> results, List<String> batchInputs, List<Integer> batchIndexes) {
        List<float[]> vectors = callEmbeddingApi(batchInputs);
        for (int i = 0; i < batchIndexes.size(); i++) {
            results.set(batchIndexes.get(i), vectors.get(i));
        }
    }

    private List<float[]> callEmbeddingApi(List<String> inputs) {
        EmbeddingRequest request = new EmbeddingRequest(embeddingModel, inputs);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            EmbeddingResponse response = openAiWebClient.post()
                    .uri("/embeddings")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new OpenAiException((HttpStatus) ex.getStatusCode(), ex.getResponseBodyAsString())))
                    .block();

            if (response == null || response.data == null || response.data.isEmpty()) {
                throw new OpenAiException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI returned empty embedding data");
            }
            if (response.data.size() != inputs.size()) {
                throw new OpenAiException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI returned mismatched embedding count");
            }

            List<float[]> vectors = new ArrayList<>(inputs.size());
            for (EmbeddingData data : response.data) {
                List<Double> embedding = data.embedding();
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = embedding.get(i).floatValue();
                }
                vectors.add(vector);
            }
            log.info("Received embeddings for batch size {}", inputs.size());
            sample.stop(embeddingTimer);
            return vectors;
        } catch (OpenAiException e) {
            embeddingErrors.increment();
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
