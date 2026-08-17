CREATE SEQUENCE resume_import_version_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE resume_imports (
    version BIGINT PRIMARY KEY DEFAULT nextval('resume_import_version_seq'),
    import_id UUID NOT NULL UNIQUE,
    candidate_profile_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    source_pdf BYTEA NOT NULL,
    page_count INTEGER NOT NULL,
    extracted_text TEXT NOT NULL,
    warnings JSONB NOT NULL,
    structured_resume JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT ck_resume_import_status CHECK (status IN ('PREVIEW', 'CONFIRMED')),
    CONSTRAINT ck_resume_import_page_count CHECK (page_count > 0),
    CONSTRAINT ck_resume_import_confirmation CHECK (
        (status = 'PREVIEW' AND confirmed_at IS NULL) OR
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
    )
);

CREATE INDEX idx_resume_imports_profile_status_version
    ON resume_imports(candidate_profile_id, status, version DESC);
