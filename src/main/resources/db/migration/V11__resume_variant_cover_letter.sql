ALTER TABLE resume_variants
    ADD COLUMN cover_letter_text TEXT,
    ADD COLUMN cover_letter_generated_at TIMESTAMPTZ;
