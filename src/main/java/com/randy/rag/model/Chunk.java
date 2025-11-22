package com.randy.rag.model;

import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("chunks")
public class Chunk {

    @Id
    private UUID id;

    @Column("document_id")
    private UUID documentId;

    @Column("chunk_index")
    private int chunkIndex;

    private String content;

    private transient float[] embedding;

    public Chunk() {
    }

    public Chunk(UUID id, UUID documentId, int chunkIndex, String content, float[] embedding) {
        this.id = id;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public static class Builder {
        private UUID id;
        private UUID documentId;
        private int chunkIndex;
        private String content;
        private float[] embedding;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder documentId(UUID documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder chunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public Chunk build() {
            return new Chunk(id, documentId, chunkIndex, content, embedding);
        }
    }
}
