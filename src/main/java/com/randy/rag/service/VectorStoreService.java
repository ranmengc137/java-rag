package com.randy.rag.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.randy.rag.model.Chunk;
import com.randy.rag.model.ChunkSearchResult;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int persistChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }

        String sql = "INSERT INTO chunks (id, document_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?, ?::vector)"
                + " ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding";

        List<Chunk> inserted = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.getEmbedding() == null || chunk.getEmbedding().length == 0) {
                log.warn("Skipping chunk {} because embedding is missing", chunk.getChunkIndex());
                continue;
            }

            // Bind values manually so we can pass the pgvector literal as a parameter.
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql);
                ps.setObject(1, chunk.getId() == null ? UUID.randomUUID() : chunk.getId());
                ps.setObject(2, chunk.getDocumentId());
                ps.setInt(3, chunk.getChunkIndex());
                ps.setString(4, chunk.getContent());
                ps.setObject(5, pgVector(chunk.getEmbedding()));
                return ps;
            });
            inserted.add(chunk);
        }
        log.info("Persisted {} chunks", inserted.size());
        return inserted.size();
    }

    public List<ChunkSearchResult> searchSimilar(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        // The "<->" operator performs pgvector cosine distance; smaller values mean more similar.
        String sql = "SELECT id, document_id, chunk_index, content, (embedding <-> ?::vector) AS distance "
                + "FROM chunks ORDER BY embedding <-> ?::vector LIMIT ?";
        PGobject vector = pgVector(queryEmbedding);
        RowMapper<ChunkSearchResult> mapper = (rs, rowNum) -> {
            double distance = rs.getDouble("distance");
            // Convert pgvector distance into a human-friendly similarity score within (0,1].
            double similarity = 1 / (1 + distance);
            return new ChunkSearchResult(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("document_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    similarity);
        };

        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setObject(1, vector);
            ps.setObject(2, vector);
            ps.setInt(3, topK);
            return ps;
        }, mapper);
    }

    private PGobject pgVector(float[] vector) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("vector");
            // pgvector expects a literal like "[0.1,0.2,...]" so we stringify before binding.
            pgObject.setValue(toPgLiteral(vector));
            return pgObject;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to convert embedding to pgvector", e);
        }
    }

    private String toPgLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            builder.append(vector[i]);
            if (i < vector.length - 1) {
                builder.append(',');
            }
        }
        builder.append(']');
        return builder.toString();
    }
}
