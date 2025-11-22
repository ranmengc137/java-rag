package com.randy.rag.repository.graph;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.randy.rag.model.graph.RelationEntity;

public interface RelationRepository extends JpaRepository<RelationEntity, UUID> {

    @Query("""
            SELECT COUNT(r) FROM RelationEntity r
            WHERE LOWER(r.subject.name) = LOWER(:subject)
              AND LOWER(r.predicate) IN :predicates
              AND (:documentId IS NULL OR r.documentId = :documentId)
            """)
    long countBySubjectAndPredicates(
            @Param("subject") String subject,
            @Param("predicates") java.util.List<String> predicates,
            @Param("documentId") java.util.UUID documentId);
}
