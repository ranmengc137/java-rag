package com.randy.rag.controller;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.randy.rag.model.Chunk;
import com.randy.rag.model.UploadResponse;
import com.randy.rag.service.ChunkService;
import com.randy.rag.service.EmbeddingService;
import com.randy.rag.service.PdfService;
import com.randy.rag.service.VectorStoreService;

@RestController
@RequestMapping("/upload")
@Validated
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final PdfService pdfService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    public UploadController(PdfService pdfService,
                            ChunkService chunkService,
                            EmbeddingService embeddingService,
                            VectorStoreService vectorStoreService) {
        this.pdfService = pdfService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file is required");
        }
        try {
            UUID documentId = UUID.randomUUID();
            // The ingestion pipeline: extract > chunk > embed > store.
            // 1. Extract: convert uploaded binary PDF into plain text using PDFBox.
            String extractedText = pdfService.extractText(file.getInputStream());
            // 2. Chunk: split the document into overlapping segments to retain relevant context.
            List<Chunk> chunks = chunkService.chunk(documentId, extractedText);
            // 3. Embed: call OpenAI for each chunk to generate dense vectors.
            chunks.forEach(chunk -> chunk.setEmbedding(embeddingService.embed(chunk.getContent())));
            // 4. Store: persist both textual content and embeddings in pgvector for future retrieval.
            int count = vectorStoreService.persistChunks(chunks);
            log.info("Uploaded document {} with {} chunks", documentId, count);
            return ResponseEntity.ok(new UploadResponse(documentId, count));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read uploaded PDF", e);
        }
    }
}
