package com.randy.rag.model;

import java.util.UUID;

public record ChunkSearchResult(
        UUID chunkId,
        UUID documentId,
        int chunkIndex,
        String content,
        double similarity) {
}
