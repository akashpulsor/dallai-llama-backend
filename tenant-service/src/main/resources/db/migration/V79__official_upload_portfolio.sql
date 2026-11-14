-- Creators' uploaded portfolio films go on Dalaillama's YouTube channel too (stamped with the
-- Dalaillama mark by post-production first), so the landing page streams them from YouTube instead
-- of our full-size files. Such a job points at the showcase item, not a project: exactly one of the two.
ALTER TABLE official_upload_job ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE official_upload_job ADD COLUMN showcase_item_id UUID;
ALTER TABLE official_upload_job ADD CONSTRAINT official_upload_job_item_uk UNIQUE (showcase_item_id);
ALTER TABLE official_upload_job ADD CONSTRAINT official_upload_job_item_fk FOREIGN KEY (showcase_item_id)
    REFERENCES showcase_item (id) ON DELETE CASCADE;
ALTER TABLE official_upload_job ADD CONSTRAINT official_upload_job_one_source
    CHECK ((project_id IS NULL) <> (showcase_item_id IS NULL));
