-- Databases migrated before query_embedding was part of V1 are missing the column.
-- Fresh schemas already have it, so both statements are idempotent.
ALTER TABLE ai_audit_logs
    ADD COLUMN IF NOT EXISTS query_embedding public.vector(${embedding_dimensions});

CREATE INDEX IF NOT EXISTS ai_audit_logs_embedding_hnsw ON ai_audit_logs
    USING hnsw (query_embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
