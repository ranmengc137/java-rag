package com.randy.rag.repository.graph;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.randy.rag.model.graph.DocumentEntity;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
    Optional<DocumentEntity> findByTitleIgnoreCase(String title);
    Optional<DocumentEntity> findByExternalId(String externalId);
}
