ALTER TABLE resume_imports
    ADD COLUMN text_blocks JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN extraction_method VARCHAR(20) NOT NULL DEFAULT 'TEXT_LAYER';

ALTER TABLE resume_imports
    ADD CONSTRAINT ck_resume_import_extraction_method
        CHECK (extraction_method IN ('TEXT_LAYER', 'OCR'));
