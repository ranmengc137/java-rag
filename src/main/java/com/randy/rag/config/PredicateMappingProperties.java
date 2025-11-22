package com.randy.rag.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "predicate.map")
public class PredicateMappingProperties {
    /**
     * Map of canonical predicate -> list of synonyms/keywords.
     * Example:
     * predicate.map.children=child,children,son,daughter
     */
    private Map<String, List<String>> predicates;

    public Map<String, List<String>> getPredicates() {
        return predicates;
    }

    public void setPredicates(Map<String, List<String>> predicates) {
        this.predicates = predicates;
    }
}
