ALTER TABLE application_runs
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX uq_application_runs_idempotency
    ON application_runs(draft_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE application_runs ADD CONSTRAINT ck_application_run_idempotency_fingerprint CHECK (
    idempotency_key IS NULL OR request_fingerprint IS NOT NULL
);
