package com.randy.rag.model.graph;

import java.util.UUID;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "documents")
public class DocumentEntity {
    @Id
    private UUID id;

    private String title;
    private String sourceType;
    private String externalId;
    private String category;
    private String kgStatus;
    private LocalDateTime kgStartedAt;
    private LocalDateTime kgCompletedAt;
    private String kgError;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getKgStatus() {
        return kgStatus;
    }

    public void setKgStatus(String kgStatus) {
        this.kgStatus = kgStatus;
    }

    public LocalDateTime getKgStartedAt() {
        return kgStartedAt;
    }

    public void setKgStartedAt(LocalDateTime kgStartedAt) {
        this.kgStartedAt = kgStartedAt;
    }

    public LocalDateTime getKgCompletedAt() {
        return kgCompletedAt;
    }

    public void setKgCompletedAt(LocalDateTime kgCompletedAt) {
        this.kgCompletedAt = kgCompletedAt;
    }

    public String getKgError() {
        return kgError;
    }

    public void setKgError(String kgError) {
        this.kgError = kgError;
    }
}
