CREATE TABLE candidate_profiles (
    id UUID PRIMARY KEY,
    profile JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE vacancies (
    id UUID PRIMARY KEY,
    source VARCHAR(50) NOT NULL,
    external_id VARCHAR(255),
    url TEXT,
    company VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    location VARCHAR(255),
    employment_type VARCHAR(50),
    salary_from NUMERIC(19, 2),
    salary_to NUMERIC(19, 2),
    salary_currency VARCHAR(3),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_vacancy_source_external UNIQUE (source, external_id)
);

CREATE TABLE vacancy_analyses (
    id UUID PRIMARY KEY,
    vacancy_id UUID NOT NULL UNIQUE REFERENCES vacancies(id) ON DELETE CASCADE,
    analysis JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE match_results (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    vacancy_id UUID NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_match_profile_vacancy UNIQUE (candidate_profile_id, vacancy_id)
);

CREATE INDEX idx_vacancies_created_at ON vacancies(created_at DESC);
CREATE INDEX idx_match_results_vacancy_id ON match_results(vacancy_id);
