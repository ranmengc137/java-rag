package com.randy.rag.repository.graph;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.randy.rag.model.graph.EventParticipant;

public interface EventParticipantRepository extends JpaRepository<EventParticipant, Long> {

    @Query("""
            SELECT COUNT(DISTINCT ep.event.id)
            FROM EventParticipant ep
            WHERE ep.actorEntity.id = :actorId
              AND ep.outcome = :outcome
              AND ep.event.eventType = :eventType
              AND (:documentId IS NULL OR ep.document.id = :documentId)
            """)
    long countDistinctEventsByActorOutcomeAndType(
            @Param("actorId") Long actorId,
            @Param("outcome") String outcome,
            @Param("eventType") String eventType,
            @Param("documentId") java.util.UUID documentId);
}
