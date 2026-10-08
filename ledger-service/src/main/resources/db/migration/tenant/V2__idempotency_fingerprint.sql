-- SHA-256 of the canonical API request that created the entry. A retry with the same
-- Idempotency-Key and the same fingerprint replays the original response; the same key
-- with a different fingerprint is rejected (IDEMPOTENCY_KEY_REUSED). NULL for entries
-- not created through the API.
ALTER TABLE journal_entries
    ADD COLUMN request_hash BYTEA CHECK (request_hash IS NULL OR length(request_hash) = 32);
