# Java RAG Backend

Spring Boot implementation of a Retrieval-Augmented Generation (RAG) backend that ingests PDFs, indexes chunk embeddings in PostgreSQL + pgvector, and answers user questions using OpenAI GPT models.

## Features

- PDF ingestion via REST (`/upload`) with Apache PDFBox
- Text chunking + OpenAI embeddings
- Vector similarity search using PostgreSQL + pgvector
- Question answering endpoint (`/query`) with GPT-based generation
- Clean separation of layers: controller, service, repository, config

## Stack
- Java 17, Spring Boot 3
- Spring Web, Spring Data JDBC, WebClient
- PostgreSQL 16 + pgvector
- Apache PDFBox for text extraction
- OpenAI Embeddings + Chat Completions APIs
- Maven, Docker Compose

## Architecture
```
+-----------+      +-------------+      +------------------+      +---------------+
|  Client   | ---> |  Upload API | ---> |  Chunk + Embed   | ---> | PostgreSQL +  |
| (REST)    |      |  /upload    |      |  Vector Store    |      |   pgvector    |
+-----------+      +-------------+      +------------------+      +-------+-------+
       |                                                        ^          |
       v                                                        |          |
+-----------+      +-------------+      +------------------+     |   Similarity
|  Client   | ---> |  Query API  | ---> |  Retriever + LLM | ----+   search
| (REST)    |      |  /query     |      |  Prompt Builder  | -> OpenAI GPT
+-----------+      +-------------+      +------------------+
```

## Prerequisites
1. Java 17+
2. Maven 3.9+
3. Docker & Docker Compose
4. OpenAI API key with access to embeddings + chat models

## Environment Variables
Set these before running the app (e.g., in `.env` or shell profile):
```
export OPENAI_API_KEY=<your-key>
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ragdb
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
```

## Run PostgreSQL + pgvector
```
cd docker
docker compose up -d
```
This launches PostgreSQL 16 with pgvector enabled and exposed on `localhost:5432`.

## Build & Run Spring Boot App
```
cd java-rag
mvn clean package
mvn spring-boot:run
```
The API becomes available at `http://localhost:8080`.

## Upload PDFs
```
curl -X POST http://localhost:8080/upload \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -F "file=@/path/to/document.pdf"
```
Response:
```
{"documentId":"<uuid>","chunksStored":42}
```

## Ask Questions
```
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -d '{"query":"What are the key risks?","topK":5}'
```
Response:
```
{
  "answer": "...",
  "sources": [
    {"chunkIndex":0,"similarity":0.82}
  ]
}
```

## Project Structure
```
java-rag/
├── docker/docker-compose.yml
├── pom.xml
├── README.md
└── src/main/java/com/randy/rag
    ├── config
    ├── controller
    ├── model
    ├── repository
    ├── service
    └── RagApplication.java
```

## Future Improvements
1. **Batch Embedding Calls** – send chunk batches to OpenAI to reduce latency.
2. **Streaming Chat Responses** – stream answers to clients for faster perceived performance.
3. **Auth & Rate Limiting** – secure endpoints before production use.
4. **Observability** – metrics/tracing for ingestion + query pipelines.
5. **Vector Cache** – add Redis/KeyDB cache for frequently asked queries.
