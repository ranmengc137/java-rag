package com.randy.rag.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "Query text is required") String query,
        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 20, message = "topK cannot exceed 20")
        Integer topK,
        String category) {

    public int resolvedTopK() {
        return topK == null ? 5 : topK;
    }
}
