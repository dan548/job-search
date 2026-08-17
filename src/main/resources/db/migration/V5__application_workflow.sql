CREATE SEQUENCE application_draft_version_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE application_drafts (
    version BIGINT PRIMARY KEY DEFAULT nextval('application_draft_version_seq'),
    draft_id UUID NOT NULL UNIQUE,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    vacancy_id UUID NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    resume_variant_id UUID NOT NULL,
    resume_variant_version BIGINT NOT NULL REFERENCES resume_variants(version),
    status VARCHAR(20) NOT NULL,
    form_url TEXT,
    observed_fields JSONB NOT NULL,
    answers JSONB NOT NULL,
    state_fingerprint VARCHAR(64) NOT NULL,
    failure_reason TEXT,
    submission JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_application_draft_status CHECK (
        status IN ('DRAFT', 'FILLING', 'NEEDS_INPUT', 'READY_TO_SUBMIT', 'SUBMITTED', 'FAILED')
    ),
    CONSTRAINT ck_application_draft_submission CHECK (
        (status = 'SUBMITTED' AND submission IS NOT NULL) OR
        (status <> 'SUBMITTED' AND submission IS NULL)
    )
);

-- At most one open application per vacancy; submitted and failed drafts stay as history.
CREATE UNIQUE INDEX uq_application_draft_open
    ON application_drafts(candidate_profile_id, vacancy_id)
    WHERE status NOT IN ('SUBMITTED', 'FAILED');

CREATE INDEX idx_application_drafts_vacancy_version ON application_drafts(vacancy_id, version DESC);

CREATE TABLE application_runs (
    run_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    form_url TEXT,
    observed_fields JSONB NOT NULL,
    filled_field_keys JSONB NOT NULL,
    pending_field_keys JSONB NOT NULL,
    messages JSONB NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_application_run_status CHECK (
        status IN ('RUNNING', 'NEEDS_INPUT', 'COMPLETED', 'FAILED')
    )
);

CREATE INDEX idx_application_runs_draft_started ON application_runs(draft_id, started_at DESC);

CREATE TABLE application_approvals (
    approval_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    question TEXT NOT NULL,
    field_key VARCHAR(255),
    topic VARCHAR(40),
    reason VARCHAR(40),
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    options JSONB NOT NULL,
    catalog_key VARCHAR(160),
    state_fingerprint VARCHAR(64),
    decision_value TEXT,
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    CONSTRAINT ck_application_approval_type CHECK (type IN ('ANSWER', 'SUBMIT')),
    CONSTRAINT ck_application_approval_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')
    ),
    CONSTRAINT ck_application_approval_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_application_approval_submit_fingerprint CHECK (
        type <> 'SUBMIT' OR state_fingerprint IS NOT NULL
    )
);

CREATE INDEX idx_application_approvals_draft_status ON application_approvals(draft_id, status);

CREATE TABLE application_artifacts (
    artifact_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES application_drafts(draft_id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_size INTEGER NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_application_artifact_type CHECK (
        type IN ('RESUME_PDF', 'COVER_LETTER', 'SCREENSHOT', 'SUBMISSION_RECEIPT')
    ),
    CONSTRAINT ck_application_artifact_size CHECK (byte_size > 0)
);

CREATE INDEX idx_application_artifacts_draft_created ON application_artifacts(draft_id, created_at);

CREATE TABLE application_settings (
    candidate_profile_id UUID PRIMARY KEY REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    settings JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE application_answer_catalog (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    answer_key VARCHAR(160) NOT NULL,
    question TEXT NOT NULL,
    answer_value TEXT NOT NULL,
    topic VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_application_answer_catalog_key UNIQUE (candidate_profile_id, answer_key)
);
