package com.randy.rag.repository.graph;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.randy.rag.model.graph.EntityAlias;

public interface EntityAliasRepository extends JpaRepository<EntityAlias, Long> {
    List<EntityAlias> findByAliasIgnoreCase(String alias);
}
