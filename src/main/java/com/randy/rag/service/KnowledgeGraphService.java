package com.randy.rag.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.randy.rag.model.graph.DocumentEntity;
import com.randy.rag.model.graph.EntityAlias;
import com.randy.rag.model.graph.GraphEntity;
import com.randy.rag.repository.graph.DocumentRepository;
import com.randy.rag.repository.graph.EntityAliasRepository;
import com.randy.rag.repository.graph.EventParticipantRepository;
import com.randy.rag.repository.graph.GraphEntityRepository;
import com.randy.rag.repository.graph.RelationRepository;

@Service
public class KnowledgeGraphService {

    private static final String LOSS_OUTCOME = "loss";
    private static final String EVENT_TYPE_BATTLE = "battle";

    private final GraphEntityRepository graphEntityRepository;
    private final EntityAliasRepository entityAliasRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final DocumentRepository documentRepository;
    private final RelationRepository relationRepository;

    public KnowledgeGraphService(GraphEntityRepository graphEntityRepository,
                                 EntityAliasRepository entityAliasRepository,
                                 EventParticipantRepository eventParticipantRepository,
                                 DocumentRepository documentRepository,
                                 RelationRepository relationRepository) {
        this.graphEntityRepository = graphEntityRepository;
        this.entityAliasRepository = entityAliasRepository;
        this.eventParticipantRepository = eventParticipantRepository;
        this.documentRepository = documentRepository;
        this.relationRepository = relationRepository;
    }

    @Transactional(readOnly = true)
    public Optional<KgCountAnswer> countBattlesLostByCharacter(String nameOrAlias, java.util.UUID documentId) {
        Optional<GraphEntity> entity = resolveEntity(nameOrAlias);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        long count = eventParticipantRepository.countDistinctEventsByActorOutcomeAndType(
                entity.get().getId(), LOSS_OUTCOME, EVENT_TYPE_BATTLE, documentId);
        return Optional.of(new KgCountAnswer(entity.get().getName(), count, documentId));
    }

    @Transactional(readOnly = true)
    public Optional<KgCountAnswer> countRelations(String subjectName, String objectPhrase, java.util.UUID documentId) {
        Optional<GraphEntity> subject = resolveEntity(subjectName);
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        List<String> predicates = predicateSynonyms(objectPhrase);
        long count = relationRepository.countBySubjectAndPredicates(subject.get().getName(), predicates, documentId);
        return Optional.of(new KgCountAnswer(subject.get().getName(), count, documentId));
    }

    @Transactional(readOnly = true)
    public Optional<DocumentEntity> findDocumentByTitle(String title) {
        return documentRepository.findByTitleIgnoreCase(title);
    }

    private Optional<GraphEntity> resolveEntity(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            return Optional.empty();
        }
        String canonical = canonicalKey(nameOrAlias);
        Optional<GraphEntity> byKey = graphEntityRepository.findByCanonicalKey(canonical);
        if (byKey.isPresent()) {
            return byKey;
        }

        List<GraphEntity> byName = graphEntityRepository.findByNameIgnoreCase(nameOrAlias);
        if (!byName.isEmpty()) {
            return Optional.of(byName.get(0));
        }

        List<EntityAlias> aliases = entityAliasRepository.findByAliasIgnoreCase(nameOrAlias);
        if (!aliases.isEmpty()) {
            return Optional.ofNullable(aliases.get(0).getEntity());
        }
        return Optional.empty();
    }

    private String canonicalKey(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        return normalized.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private List<String> predicateSynonyms(String objectPhrase) {
        String cleaned = objectPhrase == null ? "" : objectPhrase.trim().toLowerCase();
        if (cleaned.contains("son") || cleaned.contains("child") || cleaned.contains("daughter")) {
            return List.of("child", "son", "daughter", "children");
        }
        return List.of(cleaned);
    }

    public record KgCountAnswer(String characterName, long count, java.util.UUID documentId) {
    }
}
