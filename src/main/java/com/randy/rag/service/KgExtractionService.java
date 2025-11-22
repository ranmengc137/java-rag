package com.randy.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

@Service
public class KgExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KgExtractionService.class);

    private final WebClient openAiWebClient;
    private final String chatModel;
    private final ObjectMapper objectMapper;

    public KgExtractionService(WebClient openAiWebClient,
                               @Value("${openai.chat-model}") String chatModel,
                               ObjectMapper objectMapper) {
        this.openAiWebClient = openAiWebClient;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(List<ChunkRow> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
        }
        log.info("Starting KG extraction on {} chunks", chunks.size());
        StringBuilder sb = new StringBuilder();
        chunks.forEach(c -> {
            sb.append("[chunk ").append(c.chunkIndex()).append("] ").append(c.content()).append("\n\n");
        });

        String prompt = """
                You are an information extraction system. Read the provided chunks and extract structured knowledge graph data.
                Output ONLY valid JSON in the following shape:
                {
                  "entities":[{"name":"","canonicalKey":"","entityType":"","description":"","aliases":[""]}],
                  "events":[{"eventType":"","eventCategory":"","name":"","chapter":"","location":"","startYear":null,"endYear":null}],
                  "participants":[{"eventName":"","actorName":"","role":"","outcome":"","chunkIndex":0}],
                  "relations":[{"subjectName":"","predicate":"","objectName":"","objectText":"","chunkIndex":0}]
                }
                Guidelines:
                - Use lowercase with underscores for canonicalKey (e.g., "cao_cao").
                - eventType is a short label like "battle", "incident", "visit".
                - Link participants to events by matching eventName and actorName.
                - chunkIndex should reference the chunk number where you saw the fact.
                Text:
                """ + sb;

        ChatCompletionRequest request = new ChatCompletionRequest(chatModel, List.of(
                new ChatMessage("system", "Extract structured entities, events, participants, and relations from the text. Output JSON only."),
                new ChatMessage("user", prompt)
        ), 1.0);

        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new EmbeddingService.OpenAiException((HttpStatus) ex.getStatusCode(), ex.getResponseBodyAsString())))
                    .block();
            if (response == null || response.isBlank()) {
                log.warn("KG extraction returned empty response");
                return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
            }
            ChatCompletionResponse parsed = objectMapper.readValue(response, ChatCompletionResponse.class);
            if (parsed.choices == null || parsed.choices.isEmpty()) {
                log.warn("KG extraction returned no choices");
                return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
            }
            String content = parsed.choices.get(0).message.content;
            if (content == null || content.isBlank()) {
                log.warn("KG extraction returned blank content");
                return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
            }
            ExtractionResult result = parseContent(content);
            log.info("KG extraction parsed entities={} events={} participants={} relations={}",
                    safeSize(result.entities()), safeSize(result.events()), safeSize(result.participants()), safeSize(result.relations()));
            return result;
        } catch (Exception e) {
            log.error("KG extraction failed to parse response: {}", e.getMessage());
            return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ChunkRow(java.util.UUID id, int chunkIndex, String content) {
    }

    public record ExtractionResult(
            List<ExtractedEntity> entities,
            List<ExtractedEvent> events,
            List<ExtractedParticipant> participants,
            List<ExtractedRelation> relations) {
    }

    public record ExtractedEntity(String name, String canonicalKey, String entityType, String description, List<String> aliases) {
    }

    public record ExtractedEvent(String eventType, String eventCategory, String name, String chapter, String location, Integer startYear, Integer endYear) {
    }

    public record ExtractedParticipant(String eventName, String actorName, String role, String outcome, Integer chunkIndex) {
    }

    public record ExtractedRelation(String subjectName, String predicate, String objectName, String objectText, Integer chunkIndex) {
    }

    private int safeSize(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private ExtractionResult parseContent(String content) {
        try {
            return objectMapper.readValue(content, ExtractionResult.class);
        } catch (Exception e) {
            log.warn("Primary parse failed, attempting fallback JSON extraction. Content snippet: {}", snippet(content));
            String fallback = extractJson(content);
            if (fallback == null) {
                log.error("KG extraction content could not be parsed as JSON");
                return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
            }
            try {
                return objectMapper.readValue(fallback, ExtractionResult.class);
            } catch (Exception ex) {
                log.error("Fallback parse failed: {}", ex.getMessage());
                return new ExtractionResult(List.of(), List.of(), List.of(), List.of());
            }
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String snippet(String text) {
        if (text == null) return "";
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
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
