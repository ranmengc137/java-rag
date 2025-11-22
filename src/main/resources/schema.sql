CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id);

-- Knowledge graph tables (shared across all documents)
CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    title TEXT,
    source_type TEXT,
    external_id TEXT,
    category TEXT,
    kg_status TEXT,
    kg_started_at TIMESTAMP,
    kg_completed_at TIMESTAMP,
    kg_error TEXT
);

CREATE TABLE IF NOT EXISTS entities (
    id BIGSERIAL PRIMARY KEY,
    entity_type TEXT,
    name TEXT,
    canonical_key TEXT,
    description TEXT
);

CREATE TABLE IF NOT EXISTS entity_aliases (
    id BIGSERIAL PRIMARY KEY,
    entity_id BIGINT REFERENCES entities(id),
    alias TEXT,
    language TEXT
);

CREATE INDEX IF NOT EXISTS idx_entities_canonical_key ON entities(canonical_key);
CREATE INDEX IF NOT EXISTS idx_entity_aliases_alias ON entity_aliases(alias);

-- Generic events layer (supersedes domain-specific battles)
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    entity_id BIGINT REFERENCES entities(id),
    document_id UUID REFERENCES documents(id),
    event_type TEXT,      -- e.g., "battle", "checkup", "incident"
    event_category TEXT,  -- optional taxonomy
    name TEXT,
    chapter TEXT,
    location TEXT,
    start_year INTEGER,
    end_year INTEGER
);

CREATE TABLE IF NOT EXISTS event_participants (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT REFERENCES events(id),
    actor_entity_id BIGINT REFERENCES entities(id),
    role TEXT,            -- e.g., "attacker", "defender", "doctor", "parent"
    outcome TEXT,         -- domain-specific result, e.g., "win", "loss", "success"
    document_id UUID REFERENCES documents(id),
    chunk_id UUID,
    note TEXT
);

CREATE INDEX IF NOT EXISTS idx_events_document_id ON events(document_id);
CREATE INDEX IF NOT EXISTS idx_event_participants_actor_outcome_doc ON event_participants(actor_entity_id, outcome, document_id);

CREATE TABLE IF NOT EXISTS relations (
    id UUID PRIMARY KEY,
    subject_entity_id BIGINT REFERENCES entities(id),
    predicate TEXT,
    object_entity_id BIGINT REFERENCES entities(id),
    object_text TEXT,
    document_id UUID REFERENCES documents(id),
    chunk_id UUID
);
CREATE INDEX IF NOT EXISTS idx_relations_subject_predicate ON relations(subject_entity_id, predicate);
CREATE INDEX IF NOT EXISTS idx_relations_document_id ON relations(document_id);

CREATE TABLE IF NOT EXISTS kg_run_history (
    id BIGSERIAL PRIMARY KEY,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    status TEXT,
    processed_count INT DEFAULT 0,
    error_summary TEXT
);
