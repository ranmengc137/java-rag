package com.randy.rag.repository.graph;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.randy.rag.model.graph.GraphEntity;

public interface GraphEntityRepository extends JpaRepository<GraphEntity, Long> {
    Optional<GraphEntity> findByCanonicalKey(String canonicalKey);
    List<GraphEntity> findByNameIgnoreCase(String name);
    List<GraphEntity> findByCanonicalKeyIn(List<String> keys);
}
