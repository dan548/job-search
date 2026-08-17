ALTER TABLE browser_sessions
    ADD COLUMN field_states JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN stop_reason VARCHAR(40);

ALTER TABLE browser_sessions ADD CONSTRAINT ck_browser_session_stop_reason CHECK (
    stop_reason IS NULL OR stop_reason IN (
        'CHALLENGE', 'VALIDATION_ERRORS', 'PENDING_ANSWERS', 'RESCAN_LIMIT',
        'RUNNER_ERROR', 'SUBMIT_ERROR'
    )
);

CREATE SEQUENCE browser_diagnostic_snapshot_seq START WITH 1 INCREMENT BY 1;

-- Only a PII-free summary is stored: origin (without query/fragment), a path hash, hashed field
-- keys, control types/statuses and stable validation codes. Labels, locators and values are absent.
CREATE TABLE browser_diagnostic_snapshots (
    snapshot_seq BIGINT PRIMARY KEY DEFAULT nextval('browser_diagnostic_snapshot_seq'),
    draft_id UUID NOT NULL REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    run_id UUID,
    origin TEXT NOT NULL,
    path_hash VARCHAR(64) NOT NULL,
    checkpoint VARCHAR(64) NOT NULL,
    observation_digest VARCHAR(64) NOT NULL,
    fields JSONB NOT NULL,
    challenges JSONB NOT NULL,
    validation_error_codes JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_browser_diagnostic_snapshots_draft
    ON browser_diagnostic_snapshots(draft_id, snapshot_seq);
