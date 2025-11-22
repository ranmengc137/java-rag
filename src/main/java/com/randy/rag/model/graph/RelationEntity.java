package com.randy.rag.model.graph;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "relations")
public class RelationEntity {
    @Id
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "subject_entity_id")
    private GraphEntity subject;

    private String predicate;

    @ManyToOne
    @JoinColumn(name = "object_entity_id")
    private GraphEntity objectEntity;

    private String objectText;

    private UUID documentId;
    private UUID chunkId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GraphEntity getSubject() {
        return subject;
    }

    public void setSubject(GraphEntity subject) {
        this.subject = subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
    }

    public GraphEntity getObjectEntity() {
        return objectEntity;
    }

    public void setObjectEntity(GraphEntity objectEntity) {
        this.objectEntity = objectEntity;
    }

    public String getObjectText() {
        return objectText;
    }

    public void setObjectText(String objectText) {
        this.objectText = objectText;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public void setChunkId(UUID chunkId) {
        this.chunkId = chunkId;
    }
}
