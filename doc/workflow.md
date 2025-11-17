# RAG Workflow Overview

```
        +-----------------+           +------------------+           +-----------------------+
        | 1. Upload PDF   |           | 2. Extract Text  |           | 3. Chunk Generation   |
        | /upload endpoint|  --> ---> | PdfService       | --> --->  | ChunkService          |
        | (Multipart PDF) |           |  - PDFBox        |           |  - Normalize whitespace|
        +-----------------+           +------------------+           |  - Overlap windows    |
                                                                        +-----------------------+
                                                                                   |
                                                                                   v
                                     +-----------------------+         +-------------------------+
                                     | 4. Embedding Service  |  --->   | 5. Vector Store (pgvector)
                                     | EmbeddingService      |         | VectorStoreService       |
                                     |  - OpenAI embeddings  |         |  - Persist chunks        |
                                     |  - 1536-dim vectors   |         |  - Chunk metadata        |
                                     +-----------------------+         |  - Embedding vectors     |
                                                                        +-----------+-------------+
                                                                                    |
                                                                                    v
       +-----------------+             +-----------------------+          +------------------------------+
       | 6. Query API    |  --> --->   | 7. Retrieval          |  --> --> | 8. LLM Prompt + Response     |
       | /query endpoint |             | VectorStoreService    |          | QueryService                 |
       | (JSON question) |             |  - pgvector search    |          |  - Build context prompt      |
       +-----------------+             |  - topK matches       |          |  - OpenAI chat completion    |
                                       +-----------------------+          +--------------+--------------+
                                                                                      |
                                                                                      v
                                                                                +-----------+
                                                                                | 9. Result |
                                                                                | Answer +  |
                                                                                | citations |
                                                                                +-----------+
```

## Step-by-step Operations
1. **/upload** — Receive a multipart PDF file and generate a new `documentId`.
2. **PDF Text Extraction** — `PdfService` uses Apache PDFBox to convert each page into plain text.
3. **Chunking** — `ChunkService` normalizes whitespace, creates overlapping segments (size 400 / overlap 100), and tags them with sequential indices.
4. **Embedding** — `EmbeddingService` sends each chunk to OpenAI's Embeddings API (`text-embedding-3-small`) and stores the returned 1536-dimensional vectors.
5. **Vector Storage** — `VectorStoreService` writes chunk metadata plus embeddings into PostgreSQL with the `pgvector` extension.
6. **/query** — Accepts JSON containing the user question and desired `topK` chunks.
7. **Retrieval** — Query text is embedded, then `VectorStoreService` executes `ORDER BY embedding <-> query` to pull the closest semantic matches.
8. **LLM Prompting** — `QueryService` assembles the retrieved chunks into a grounded prompt and calls the OpenAI Chat Completions API (e.g., GPT-4o mini).
9. **Response** — API returns the generated answer plus citations (chunk indices + similarity scores) so consumers can trace supporting evidence.
