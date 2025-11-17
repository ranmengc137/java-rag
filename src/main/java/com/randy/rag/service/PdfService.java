package com.randy.rag.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    public String extractText(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("Extracted {} characters from PDF", text.length());
            return text;
        } catch (IOException e) {
            log.error("Failed to extract text from PDF", e);
            throw new PdfTextExtractionException("Unable to read PDF content", e);
        }
    }

    public static class PdfTextExtractionException extends RuntimeException {
        public PdfTextExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
