package com.randy.rag.controller;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final JdbcTemplate jdbcTemplate;

    public CategoryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<String> listCategories() {
        return jdbcTemplate.query("SELECT DISTINCT category FROM documents WHERE category IS NOT NULL AND category <> '' ORDER BY category",
                (rs, rowNum) -> rs.getString(1));
    }
}
