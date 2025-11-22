package com.randy.rag.repository.graph;

import org.springframework.data.jpa.repository.JpaRepository;

import com.randy.rag.model.graph.EventEntity;

public interface EventRepository extends JpaRepository<EventEntity, Long> {
}
