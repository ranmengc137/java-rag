package com.randy.rag.model;

import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}
