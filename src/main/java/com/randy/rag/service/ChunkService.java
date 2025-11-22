package com.randy.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.randy.rag.model.Chunk;

@Service
public class ChunkService {

    private final int chunkSize;
    private final int chunkOverlap;

    public ChunkService(@Value("${rag.chunk.size}") int chunkSize,
                        @Value("${rag.chunk.overlap}") int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<Chunk> chunk(UUID documentId, String text) {
        // Normalize whitespace so chunk boundaries are driven by semantic content not formatting noise.
        // This keeps downstream embeddings focused on meaningful tokens rather than varying line breaks.
        String normalized = sanitize(text);
        List<Chunk> chunks = new ArrayList<>();
        if (normalized.isEmpty()) {
            return chunks;
        }

        int start = 0;
        int index = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            String candidate = normalized.substring(start, end);
            if (end < normalized.length()) {
                int lastSpace = candidate.lastIndexOf(' ');
                if (lastSpace > chunkSize / 2) {
                    // Shift the boundary back to the last whitespace so we avoid splitting midway through a word.
                    // This sacrifices a few characters but yields chunks that read naturally for the LLM.
                    end = start + lastSpace;
                    candidate = normalized.substring(start, end);
                }
            }

            chunks.add(Chunk.builder()
                    .id(UUID.randomUUID())
                    .documentId(documentId)
                    .chunkIndex(index++)
                    .content(candidate.trim())
                    .build());

            if (end == normalized.length()) {
                break;
            }
            // Slide the window forward while reusing chunkOverlap characters to preserve context continuity.
            // Overlap is clamped so we never move backwards; we only re-include the tail of previous chunk.
            start = Math.max(end - chunkOverlap, end);
        }

        return chunks;
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        // Strip null bytes and control characters that PostgreSQL rejects.
        String cleaned = text.replace('\u0000', ' ').replaceAll("\\p{Cntrl}", " ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }
}
