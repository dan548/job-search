CREATE SEQUENCE resume_variant_version_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE resume_variants (
    version BIGINT PRIMARY KEY DEFAULT nextval('resume_variant_version_seq'),
    variant_id UUID NOT NULL UNIQUE,
    candidate_profile_id UUID NOT NULL,
    vacancy_id UUID NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    base_import_id UUID NOT NULL,
    base_import_version BIGINT NOT NULL REFERENCES resume_imports(version),
    template_id VARCHAR(100) NOT NULL,
    template_version INTEGER NOT NULL,
    plan JSONB NOT NULL,
    resume JSONB NOT NULL,
    diff JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_resume_variant_template_version CHECK (template_version > 0)
);

CREATE INDEX idx_resume_variants_vacancy_version ON resume_variants(vacancy_id, version DESC);
CREATE INDEX idx_resume_variants_profile_version ON resume_variants(candidate_profile_id, version DESC);
