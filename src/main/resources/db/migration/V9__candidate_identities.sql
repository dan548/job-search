ALTER TABLE candidate_profiles ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE candidate_profiles
SET active = TRUE
WHERE id = (SELECT id FROM candidate_profiles ORDER BY created_at ASC LIMIT 1);

CREATE UNIQUE INDEX uq_candidate_profiles_single_active
    ON candidate_profiles(active)
    WHERE active = TRUE;

ALTER TABLE resume_variants ALTER COLUMN base_import_id DROP NOT NULL;
ALTER TABLE resume_variants ALTER COLUMN base_import_version DROP NOT NULL;
