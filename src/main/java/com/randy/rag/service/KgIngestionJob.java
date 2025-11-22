package com.randy.rag.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.randy.rag.model.graph.DocumentEntity;
import com.randy.rag.model.graph.EntityAlias;
import com.randy.rag.model.graph.EventEntity;
import com.randy.rag.model.graph.EventParticipant;
import com.randy.rag.model.graph.GraphEntity;
import com.randy.rag.model.graph.RelationEntity;
import com.randy.rag.repository.graph.DocumentRepository;
import com.randy.rag.repository.graph.EntityAliasRepository;
import com.randy.rag.repository.graph.EventParticipantRepository;
import com.randy.rag.repository.graph.EventRepository;
import com.randy.rag.repository.graph.GraphEntityRepository;
import com.randy.rag.repository.graph.KgRunHistoryRepository;
import com.randy.rag.repository.graph.RelationRepository;
import com.randy.rag.service.KgExtractionService.ChunkRow;
import com.randy.rag.service.KgExtractionService.ExtractionResult;
import com.randy.rag.service.KgExtractionService.ExtractedEntity;
import com.randy.rag.service.KgExtractionService.ExtractedEvent;
import com.randy.rag.service.KgExtractionService.ExtractedParticipant;
import com.randy.rag.service.KgExtractionService.ExtractedRelation;

@Service
public class KgIngestionJob {

    private static final Logger log = LoggerFactory.getLogger(KgIngestionJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final DocumentRepository documentRepository;
    private final KgRunHistoryRepository historyRepository;
    private final KgExtractionService kgExtractionService;
    private final GraphEntityRepository graphEntityRepository;
    private final EntityAliasRepository entityAliasRepository;
    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final RelationRepository relationRepository;

    public KgIngestionJob(JdbcTemplate jdbcTemplate,
                          DocumentRepository documentRepository,
                          KgRunHistoryRepository historyRepository,
                          KgExtractionService kgExtractionService,
                          GraphEntityRepository graphEntityRepository,
                          EntityAliasRepository entityAliasRepository,
                          EventRepository eventRepository,
                          EventParticipantRepository eventParticipantRepository,
                          RelationRepository relationRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.historyRepository = historyRepository;
        this.kgExtractionService = kgExtractionService;
        this.graphEntityRepository = graphEntityRepository;
        this.entityAliasRepository = entityAliasRepository;
        this.eventRepository = eventRepository;
        this.eventParticipantRepository = eventParticipantRepository;
        this.relationRepository = relationRepository;
    }

    @Transactional
    public KgRunResult runOnce(int limit) {
        long runId = historyRepository.startRun();
        int processed = 0;
        try {
            List<UUID> docIds = jdbcTemplate.query(
                    "SELECT id FROM documents WHERE kg_status IS NULL OR kg_status = 'PENDING' LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, rowNum) -> (UUID) rs.getObject("id"),
                    limit);
            log.info("KG ingestion run {} picked {} documents", runId, docIds.size());
            for (UUID id : docIds) {
                markStatus(id, "PROCESSING", null);
                try {
                    processDocument(id);
                    markStatus(id, "COMPLETED", null);
                    processed++;
                } catch (Exception e) {
                    log.error("KG ingestion failed for document {}: {}", id, e.getMessage());
                    markStatus(id, "FAILED", e.getMessage());
                }
            }
            historyRepository.finishRun(runId, "COMPLETED", processed, null);
            return new KgRunResult(runId, processed, null);
        } catch (Exception e) {
            historyRepository.finishRun(runId, "FAILED", processed, e.getMessage());
            return new KgRunResult(runId, processed, e.getMessage());
        }
    }

    private void processDocument(UUID docId) {
        List<ChunkRow> chunks = jdbcTemplate.query(
                "SELECT id, chunk_index, content FROM chunks WHERE document_id = ? ORDER BY chunk_index",
                (rs, rowNum) -> new ChunkRow((UUID) rs.getObject("id"), rs.getInt("chunk_index"), rs.getString("content")),
                docId);
        log.info("Processing document {} with {} chunks for KG extraction", docId, chunks.size());
        ExtractionResult extraction = kgExtractionService.extract(chunks);
        log.info("Extraction summary for document {}: entities={}, events={}, participants={}, relations={}",
                docId,
                extraction.entities() == null ? 0 : extraction.entities().size(),
                extraction.events() == null ? 0 : extraction.events().size(),
                extraction.participants() == null ? 0 : extraction.participants().size(),
                extraction.relations() == null ? 0 : extraction.relations().size());
        upsertEntities(extraction.entities());
        Map<String, GraphEntity> entityByName = graphEntityRepository.findAll().stream()
                .collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e, (a, b) -> a));
        Map<String, GraphEntity> entityByCanonical = graphEntityRepository.findAll().stream()
                .collect(Collectors.toMap(e -> {
                    String ck = e.getCanonicalKey();
                    return ck == null ? "" : ck.toLowerCase();
                }, e -> e, (a, b) -> a));
        Map<String, EventEntity> events = createEvents(extraction.events(), docId);
        createParticipants(extraction.participants(), chunks, entityByName, entityByCanonical, events, docId);
        createRelations(extraction.relations(), chunks, entityByName, entityByCanonical, docId);
    }

    private void upsertEntities(List<ExtractedEntity> extracted) {
        for (ExtractedEntity e : extracted) {
            if (e.name() == null || e.name().isBlank()) {
                continue;
            }
            String canonical = e.canonicalKey() == null || e.canonicalKey().isBlank()
                    ? normalizeKey(e.name())
                    : e.canonicalKey().trim().toLowerCase();
            GraphEntity existing = graphEntityRepository.findByCanonicalKey(canonical).orElse(null);
            if (existing == null) {
                existing = new GraphEntity();
                existing.setName(e.name());
                existing.setCanonicalKey(canonical);
                existing.setEntityType(e.entityType());
                existing.setDescription(e.description());
                graphEntityRepository.save(existing);
            }
            if (e.aliases() != null) {
                for (String alias : e.aliases()) {
                    if (alias == null || alias.isBlank()) continue;
                    EntityAlias aliasRow = new EntityAlias();
                    aliasRow.setEntity(existing);
                    aliasRow.setAlias(alias);
                    aliasRow.setLanguage(null);
                    entityAliasRepository.save(aliasRow);
                }
            }
        }
    }

    private Map<String, EventEntity> createEvents(List<ExtractedEvent> events, UUID documentId) {
        Map<String, EventEntity> created = new java.util.HashMap<>();
        for (ExtractedEvent ev : events) {
            if (ev.name() == null || ev.name().isBlank()) continue;
            EventEntity event = new EventEntity();
            event.setDocument(documentRepository.findById(documentId).orElse(null));
            event.setName(ev.name());
            event.setEventType(ev.eventType());
            event.setEventCategory(ev.eventCategory());
            event.setChapter(ev.chapter());
            event.setLocation(ev.location());
            event.setStartYear(ev.startYear());
            event.setEndYear(ev.endYear());
            eventRepository.save(event);
            created.put(ev.name().toLowerCase(), event);
        }
        return created;
    }

    private void createParticipants(List<ExtractedParticipant> participants,
                                    List<ChunkRow> chunks,
                                    Map<String, GraphEntity> entityByName,
                                    Map<String, GraphEntity> entityByCanonical,
                                    Map<String, EventEntity> events,
                                    UUID documentId) {
        Map<Integer, UUID> chunkIdByIndex = new java.util.HashMap<>();
        chunks.forEach(c -> chunkIdByIndex.put(c.chunkIndex(), c.id()));
        for (ExtractedParticipant p : participants) {
            if (p.eventName() == null || p.actorName() == null) continue;
            GraphEntity actor = resolveEntity(p.actorName(), entityByName, entityByCanonical);
            if (actor == null) continue;
            EventEntity event = events.get(p.eventName().toLowerCase());
            if (event == null) continue;
            EventParticipant ep = new EventParticipant();
            ep.setActorEntity(actor);
            ep.setEvent(event);
            ep.setRole(p.role());
            ep.setOutcome(p.outcome());
            ep.setDocument(documentRepository.findById(documentId).orElse(null));
            if (p.chunkIndex() != null) {
                ep.setChunkId(chunkIdByIndex.get(p.chunkIndex()));
            }
            eventParticipantRepository.save(ep);
        }
    }

    private void createRelations(List<ExtractedRelation> relations,
                                 List<ChunkRow> chunks,
                                 Map<String, GraphEntity> entityByName,
                                 Map<String, GraphEntity> entityByCanonical,
                                 UUID documentId) {
        if (relations == null) return;
        Map<Integer, UUID> chunkIdByIndex = new java.util.HashMap<>();
        chunks.forEach(c -> chunkIdByIndex.put(c.chunkIndex(), c.id()));
        // Preload existing relation hashes for this document to avoid duplicates.
        java.util.Set<String> existingHashes = new java.util.HashSet<>();
        relationRepository.findAll().stream()
                .filter(r -> documentId.equals(r.getDocumentId()))
                .forEach(r -> existingHashes.add(relationHash(r.getSubject(), r.getPredicate(), r.getObjectText(), r.getObjectEntity())));
        for (ExtractedRelation r : relations) {
            if (r.subjectName() == null || r.predicate() == null) continue;
            GraphEntity subject = resolveEntity(r.subjectName(), entityByName, entityByCanonical);
            GraphEntity obj = null;
            if (r.objectName() != null && !r.objectName().isBlank()) {
                obj = resolveEntity(r.objectName(), entityByName, entityByCanonical);
            }
            String hash = relationHash(subject, r.predicate(), r.objectText(), obj);
            if (existingHashes.contains(hash)) {
                continue;
            }
            RelationEntity rel = new RelationEntity();
            rel.setId(UUID.randomUUID());
            rel.setSubject(subject);
            rel.setPredicate(r.predicate());
            rel.setObjectEntity(obj);
            rel.setObjectText(r.objectText());
            rel.setDocumentId(documentId);
            if (r.chunkIndex() != null) {
                rel.setChunkId(chunkIdByIndex.get(r.chunkIndex()));
            }
            relationRepository.save(rel);
            existingHashes.add(hash);
        }
    }

    private GraphEntity resolveEntity(String name, Map<String, GraphEntity> byName, Map<String, GraphEntity> byCanonical) {
        String key = normalizeKey(name);
        GraphEntity found = byCanonical.get(key);
        if (found != null) return found;
        found = byName.get(name.toLowerCase());
        if (found != null) return found;
        GraphEntity created = new GraphEntity();
        created.setName(name);
        created.setCanonicalKey(key);
        graphEntityRepository.save(created);
        byCanonical.put(key, created);
        byName.put(name.toLowerCase(), created);
        return created;
    }

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toLowerCase().replaceAll("\\s+", "_");
    }

    private String relationHash(GraphEntity subject, String predicate, String objectText, GraphEntity objectEntity) {
        String subj = subject == null ? "" : subject.getCanonicalKey();
        String pred = predicate == null ? "" : predicate.trim().toLowerCase();
        String obj = objectEntity != null ? objectEntity.getCanonicalKey() : (objectText == null ? "" : objectText.trim().toLowerCase());
        return subj + "|" + pred + "|" + obj;
    }

    private void markStatus(UUID docId, String status, String error) {
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setKgStatus(status);
        if ("PROCESSING".equals(status)) {
            doc.setKgStartedAt(LocalDateTime.now());
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            doc.setKgCompletedAt(LocalDateTime.now());
        }
        doc.setKgError(error);
        documentRepository.save(doc);
    }

    public record KgRunResult(long runId, int processedCount, String error) {
    }
}
