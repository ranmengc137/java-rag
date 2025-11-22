package com.randy.rag.repository.graph;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KgRunHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public KgRunHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long startRun() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO kg_run_history (started_at, status) VALUES (?, ?) RETURNING id",
                Long.class,
                LocalDateTime.now(),
                "STARTED");
    }

    public void finishRun(long id, String status, int processedCount, String errorSummary) {
        jdbcTemplate.update(
                "UPDATE kg_run_history SET completed_at = ?, status = ?, processed_count = ?, error_summary = ? WHERE id = ?",
                LocalDateTime.now(),
                status,
                processedCount,
                errorSummary,
                id);
    }

    public List<RunRecord> recentRuns(int limit) {
        return jdbcTemplate.query(
                "SELECT id, started_at, completed_at, status, processed_count, error_summary FROM kg_run_history ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new RunRecord(
                        rs.getLong("id"),
                        toTime(rs.getTimestamp("started_at")),
                        toTime(rs.getTimestamp("completed_at")),
                        rs.getString("status"),
                        rs.getInt("processed_count"),
                        rs.getString("error_summary")),
                limit);
    }

    private LocalDateTime toTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public record RunRecord(long id, LocalDateTime startedAt, LocalDateTime completedAt, String status, int processedCount, String errorSummary) {
    }
}
