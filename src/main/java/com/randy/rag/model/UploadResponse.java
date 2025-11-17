package com.randy.rag.model;

import java.util.UUID;

public record UploadResponse(UUID documentId, int chunksStored) {
}
