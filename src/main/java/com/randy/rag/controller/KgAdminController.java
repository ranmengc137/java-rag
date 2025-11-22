package com.randy.rag.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.randy.rag.repository.graph.KgRunHistoryRepository;
import com.randy.rag.service.KgIngestionJob;

@RestController
@RequestMapping("/admin/ingest/kg")
public class KgAdminController {

    private final KgIngestionJob ingestionJob;
    private final KgRunHistoryRepository historyRepository;
    private final JdbcTemplate jdbcTemplate;

    public KgAdminController(KgIngestionJob ingestionJob,
                             KgRunHistoryRepository historyRepository,
                             JdbcTemplate jdbcTemplate) {
        this.ingestionJob = ingestionJob;
        this.historyRepository = historyRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    public ResponseEntity<KgIngestionJob.KgRunResult> run(@RequestParam(value = "limit", defaultValue = "5") int limit) {
        return ResponseEntity.ok(ingestionJob.runOnce(limit));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Integer pending = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM documents WHERE kg_status IS NULL OR kg_status='PENDING'", Integer.class);
        Integer processing = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM documents WHERE kg_status='PROCESSING'", Integer.class);
        Integer completed = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM documents WHERE kg_status='COMPLETED'", Integer.class);
        Integer failed = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM documents WHERE kg_status='FAILED'", Integer.class);
        return Map.of(
                "pending", pending,
                "processing", processing,
                "completed", completed,
                "failed", failed,
                "recentRuns", historyRepository.recentRuns(5)
        );
    }

    @GetMapping("/runs")
    public Object runs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return historyRepository.recentRuns(limit);
    }
}
