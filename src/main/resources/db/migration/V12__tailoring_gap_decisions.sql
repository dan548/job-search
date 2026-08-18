CREATE TABLE tailoring_gap_decisions (
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    vacancy_id UUID NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    group_id VARCHAR(300) NOT NULL,
    decision_type VARCHAR(40) NOT NULL,
    explanation TEXT NOT NULL,
    confirmed_fact_id VARCHAR(200),
    decided_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (candidate_profile_id, vacancy_id, group_id),
    CONSTRAINT ck_tailoring_gap_decision_type CHECK (
        decision_type IN ('CONFIRMED_FACT_ADDED', 'CANNOT_CONFIRM', 'NOT_APPLICABLE', 'ACCEPT_RISK')
    )
);
