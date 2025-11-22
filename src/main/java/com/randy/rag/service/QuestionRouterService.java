package com.randy.rag.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.randy.rag.config.PredicateMappingProperties;
import com.randy.rag.model.graph.DocumentEntity;
import com.randy.rag.service.KnowledgeGraphService.KgCountAnswer;

import reactor.core.publisher.Mono;

@Service
public class QuestionRouterService {

    private final KnowledgeGraphService knowledgeGraphService;
    private final WebClient openAiWebClient;
    private final String chatModel;
    private final PredicateMappingProperties predicateMappingProperties;

    public QuestionRouterService(KnowledgeGraphService knowledgeGraphService,
                                 WebClient openAiWebClient,
                                 PredicateMappingProperties predicateMappingProperties,
                                 @Value("${openai.chat-model}") String chatModel) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.openAiWebClient = openAiWebClient;
        this.predicateMappingProperties = predicateMappingProperties;
        this.chatModel = chatModel;
    }

    public Optional<KgRoutedAnswer> routeToKnowledgeGraph(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String trimmed = question.trim();
        Optional<KgRoutedAnswer> intentRouted = routeViaIntent(trimmed);
        if (intentRouted.isPresent()) {
            return intentRouted;
        }
        return Optional.empty();
    }

    private Optional<KgRoutedAnswer> routeViaIntent(String question) {
        try {
            IntentResponse intentResponse = callIntent(question);
            IntentPayload intent = intentResponse == null ? null : intentResponse.payload();
            if (intent == null || intent.intent == null || intent.confidence < 0.4) {
                return Optional.empty();
            }
            String intentName = intent.intent.toLowerCase();
            if ("relation_count".equals(intentName)) {
                String subject = intent.subject == null ? null : intent.subject.trim();
                String object = intent.object == null ? null : intent.object.trim();
                String predicate = intent.predicate == null ? null : intent.predicate.trim();
                List<String> predicates = mapPredicates(object, predicate);
                if (subject == null || predicates.isEmpty()) {
                    return Optional.empty();
                }
                Optional<KgCountAnswer> answer = knowledgeGraphService.countRelations(subject, predicates.get(0), null);
                return answer.map(a -> new KgRoutedAnswer(a, null));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private IntentResponse callIntent(String question) {
        IntentRequest request = new IntentRequest(chatModel, List.of(
                new IntentMessage("system", """
                        You are an intent classifier for a knowledge graph QA system.
                        Output JSON only with fields: intent (relation_count or none), subject, predicate, object, confidence (0-1).
                        Example: {"intent":"relation_count","subject":"Cao Cao","predicate":"child","object":"children","confidence":0.8}
                        """),
                new IntentMessage("user", question)
        ));
        return openAiWebClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(IntentResponse.class)
                .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new EmbeddingService.OpenAiException((HttpStatus) ex.getStatusCode(), ex.getResponseBodyAsString())))
                .block();
    }

    private List<String> mapPredicates(String object, String predicate) {
        if (predicate != null && !predicate.isBlank()) {
            return List.of(predicate.toLowerCase());
        }
        String key = object == null ? "" : object.toLowerCase();
        if (predicateMappingProperties.getPredicates() != null) {
            for (var entry : predicateMappingProperties.getPredicates().entrySet()) {
                if (entry.getValue().stream().anyMatch(s -> key.contains(s.toLowerCase()))) {
                    return List.of(entry.getKey());
                }
            }
        }
        return key.isBlank() ? List.of() : List.of(key);
    }

    private String clean(String input) {
        if (input == null) {
            return null;
        }
        // Strip trailing punctuation that often follows names in questions.
        return input.trim().replaceAll("[\\?。，、！!;；]+$", "").trim();
    }

    public record KgRoutedAnswer(KgCountAnswer answer, String documentTitle) {
        public String scopeLabel() {
            if (documentTitle == null) {
                return "across all documents";
            }
            return "within \"" + documentTitle + "\"";
        }
    }

    private record IntentRequest(String model, List<IntentMessage> messages) {
    }

    private record IntentMessage(String role, String content) {
    }

    private record IntentResponse(List<IntentChoice> choices) {
        IntentPayload payload() {
            if (choices == null || choices.isEmpty() || choices.get(0).message == null || choices.get(0).message.content == null) {
                return null;
            }
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(choices.get(0).message.content, IntentPayload.class);
            } catch (Exception e) {
                return null;
            }
        }

        IntentResponse normalised() {
            IntentPayload p = payload();
            if (p == null) return null;
            return new IntentResponse(List.of(new IntentChoice(new IntentMessage("assistant", p.toString()))));
        }

        String intent() {
            IntentPayload p = payload();
            return p == null ? null : p.intent;
        }

        double confidence() {
            IntentPayload p = payload();
            return p == null ? 0 : p.confidence;
        }

        String subject() {
            IntentPayload p = payload();
            return p == null ? null : p.subject;
        }

        String predicate() {
            IntentPayload p = payload();
            return p == null ? null : p.predicate;
        }

        String object() {
            IntentPayload p = payload();
            return p == null ? null : p.object;
        }
    }

    private record IntentChoice(IntentMessage message) {
    }

    private record IntentPayload(String intent, String subject, String predicate, String object, double confidence) {
    }
}
