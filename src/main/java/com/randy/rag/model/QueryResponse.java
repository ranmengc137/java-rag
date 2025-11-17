package com.randy.rag.model;

import java.util.List;

public record QueryResponse(
        String answer,
        List<QueryResponseSource> sources) {
}
