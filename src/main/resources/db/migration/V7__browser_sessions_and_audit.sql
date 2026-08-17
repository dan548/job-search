-- Resumable browser sessions. One live session per application draft; the row survives a backend
-- restart so a run paused on CAPTCHA, OTP or re-authentication can be continued afterwards.
CREATE TABLE browser_sessions (
    session_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    form_url TEXT NOT NULL,
    current_url TEXT NOT NULL,
    checkpoint VARCHAR(64) NOT NULL,
    observation_digest VARCHAR(64) NOT NULL,
    base_idempotency_key VARCHAR(128) NOT NULL,
    challenges JSONB NOT NULL,
    last_run_id UUID,
    resume_count INTEGER NOT NULL DEFAULT 0,
    -- Cookies and local storage of the ATS session: written only when explicitly enabled and never
    -- returned through the API.
    storage_state TEXT,
    confirmation_reference TEXT,
    failure_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_browser_session_status CHECK (
        status IN ('ACTIVE', 'PAUSED', 'SUBMITTED', 'CLOSED')
    ),
    CONSTRAINT ck_browser_session_resume_count CHECK (resume_count >= 0)
);

CREATE SEQUENCE browser_audit_entry_seq START WITH 1 INCREMENT BY 1;

-- Append-only audit of everything the browser runner did. It deliberately holds field keys and
-- detail codes only: no answer value and no file content ever reaches this table.
CREATE TABLE browser_audit_entries (
    entry_seq BIGINT PRIMARY KEY DEFAULT nextval('browser_audit_entry_seq'),
    draft_id UUID NOT NULL REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    run_id UUID,
    event VARCHAR(40) NOT NULL,
    field_key VARCHAR(255),
    detail_code VARCHAR(120),
    checkpoint VARCHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_browser_audit_entries_draft ON browser_audit_entries(draft_id, entry_seq);
