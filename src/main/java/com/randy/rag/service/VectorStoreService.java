package com.randy.rag.service;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.randy.rag.model.Chunk;
import com.randy.rag.model.ChunkSearchResult;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final boolean cacheEnabled;
    private final long cacheTtlMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Timer searchTimer;
    private final Timer persistTimer;

    public VectorStoreService(JdbcTemplate jdbcTemplate,
                              @Value("${vector.cache.enabled:true}") boolean cacheEnabled,
                              @Value("${vector.cache.ttl-seconds:300}") long cacheTtlSeconds,
                              MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlMillis = cacheTtlSeconds * 1000;
        this.searchTimer = meterRegistry.timer("rag.vector.search");
        this.persistTimer = meterRegistry.timer("rag.vector.persist");
    }

    public int persistChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }

        Timer.Sample sample = Timer.start(meterRegistry);

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
        sample.stop(persistTimer);
        return inserted.size();
    }

    public List<ChunkSearchResult> searchSimilar(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        String key = cacheKey(queryEmbedding, topK, null);
        if (cacheEnabled) {
            List<ChunkSearchResult> cached = getCached(key);
            if (cached != null) {
                return cached;
            }
        }
        Timer.Sample sample = Timer.start(meterRegistry);
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

        List<ChunkSearchResult> results = jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setObject(1, vector);
            ps.setObject(2, vector);
            ps.setInt(3, topK);
            return ps;
        }, mapper);
        sample.stop(searchTimer);
        if (cacheEnabled) {
            putCached(key, results);
        }
        return results;
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

    public List<ChunkSearchResult> searchSimilar(float[] queryEmbedding, int topK, String category) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        String key = cacheKey(queryEmbedding, topK, category);
        if (cacheEnabled) {
            List<ChunkSearchResult> cached = getCached(key);
            if (cached != null) {
                return cached;
            }
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean filterByCategory = category != null && !category.isBlank();
        if (filterByCategory) {
            log.info("Vector search with category filter: {}", category);
        }
        String sql = "SELECT c.id, c.document_id, c.chunk_index, c.content, (c.embedding <-> ?::vector) AS distance "
                + "FROM chunks c "
                + (filterByCategory ? "JOIN documents d ON d.id = c.document_id " : "")
                + (filterByCategory ? "WHERE d.category = ? " : "")
                + "ORDER BY c.embedding <-> ?::vector LIMIT ?";
        PGobject vector = pgVector(queryEmbedding);
        RowMapper<ChunkSearchResult> mapper = (rs, rowNum) -> {
            double distance = rs.getDouble("distance");
            double similarity = 1 / (1 + distance);
            return new ChunkSearchResult(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("document_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    similarity);
        };

        List<ChunkSearchResult> results = jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            int idx = 1;
            ps.setObject(idx++, vector);
            if (filterByCategory) {
                ps.setString(idx++, category);
            }
            ps.setObject(idx++, vector);
            ps.setInt(idx, topK);
            return ps;
        }, mapper);
        sample.stop(searchTimer);
        if (cacheEnabled) {
            putCached(key, results);
        }
        return results;
    }

    private String cacheKey(float[] vector, int topK, String category) {
        ByteBuffer buffer = ByteBuffer.allocate(Float.BYTES * vector.length + Integer.BYTES + 64);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        buffer.putInt(topK);
        if (category != null) {
            buffer.put(category.getBytes());
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private List<ChunkSearchResult> getCached(String key) {
        CacheEntry entry = cache.get(key);
        long now = Instant.now().toEpochMilli();
        if (entry == null || entry.expiresAt < now) {
            cache.remove(key);
            return null;
        }
        return entry.results;
    }

    private void putCached(String key, List<ChunkSearchResult> results) {
        long expiresAt = Instant.now().toEpochMilli() + cacheTtlMillis;
        cache.put(key, new CacheEntry(results, expiresAt));
    }

    private record CacheEntry(List<ChunkSearchResult> results, long expiresAt) {
    }
}
