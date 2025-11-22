package com.randy.rag.controller;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.randy.rag.model.Chunk;
import com.randy.rag.model.UploadResponse;
import com.randy.rag.model.graph.DocumentEntity;
import com.randy.rag.repository.graph.DocumentRepository;
import com.randy.rag.service.ChunkService;
import com.randy.rag.service.EmbeddingService;
import com.randy.rag.service.KgIngestionJob;
import com.randy.rag.service.PdfService;
import com.randy.rag.service.CategoryClassifierService;
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
    private final DocumentRepository documentRepository;
    private final CategoryClassifierService categoryClassifierService;
    private final KgIngestionJob kgIngestionJob;

    public UploadController(PdfService pdfService,
                            ChunkService chunkService,
                            EmbeddingService embeddingService,
                            VectorStoreService vectorStoreService,
                            DocumentRepository documentRepository,
                            CategoryClassifierService categoryClassifierService,
                            KgIngestionJob kgIngestionJob) {
        this.pdfService = pdfService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.documentRepository = documentRepository;
        this.categoryClassifierService = categoryClassifierService;
        this.kgIngestionJob = kgIngestionJob;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
                                                 @RequestParam(value = "category", required = false) String category) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file is required");
        }
        try {
            UUID documentId = UUID.randomUUID();
            // The ingestion pipeline: extract > chunk > embed > store.
            byte[] fileBytes = file.getBytes();
            String fingerprint = sha256(fileBytes);
            var existing = documentRepository.findByExternalId(fingerprint);
            if (existing.isPresent()) {
                log.info("Duplicate upload detected for filename={}, existingDocument={}", file.getOriginalFilename(), existing.get().getId());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new UploadResponse(existing.get().getId(), 0));
            }
            // 1. Extract: convert uploaded binary PDF into plain text using PDFBox.
            String extractedText;
            try (InputStream in = new java.io.ByteArrayInputStream(fileBytes)) {
                extractedText = pdfService.extractText(in);
            }
            String resolvedCategory = resolveCategory(category, extractedText);
            log.info("Upload received: filename={}, documentId={}, category={}", file.getOriginalFilename(), documentId, resolvedCategory);
            persistDocument(documentId, file.getOriginalFilename(), resolvedCategory, fingerprint);
            // 2. Chunk: split the document into overlapping segments to retain relevant context.
            List<Chunk> chunks = chunkService.chunk(documentId, extractedText);
            log.info("Chunked document {} into {} chunks", documentId, chunks.size());
            // 3. Embed: batch OpenAI calls to reduce round-trips while preserving chunk order.
            List<float[]> embeddings = embeddingService.embedBatch(
                    chunks.stream().map(Chunk::getContent).toList());
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setEmbedding(embeddings.get(i));
            }
            // 4. Store: persist both textual content and embeddings in pgvector for future retrieval.
            int count = vectorStoreService.persistChunks(chunks);
            log.info("Uploaded document {} with {} chunks", documentId, count);
            // Kick off KG ingestion for pending docs asynchronously.
            runKgIngestionAsync();
            return ResponseEntity.ok(new UploadResponse(documentId, count));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read uploaded PDF", e);
        }
    }

    private String resolveCategory(String providedCategory, String textSample) {
        if (providedCategory != null && !providedCategory.isBlank()) {
            return providedCategory;
        }
        return categoryClassifierService.classify(textSample, "other");
    }

    private void persistDocument(UUID id, String filename, String category, String fingerprint) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setTitle(filename);
        doc.setCategory(category);
        doc.setSourceType("upload");
        doc.setExternalId(fingerprint);
        documentRepository.save(doc);
    }

    private void runKgIngestionAsync() {
        java.util.concurrent.CompletableFuture.runAsync(() -> kgIngestionJob.runOnce(5));
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
