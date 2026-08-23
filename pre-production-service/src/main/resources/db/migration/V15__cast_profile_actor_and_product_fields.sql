ALTER TABLE cast_profile ADD COLUMN profile_type VARCHAR(16) NOT NULL DEFAULT 'ACTOR';
ALTER TABLE cast_profile ADD COLUMN age INTEGER;
ALTER TABLE cast_profile ADD COLUMN gender VARCHAR(32);
ALTER TABLE cast_profile ADD COLUMN voice_ref_bucket VARCHAR(255);
ALTER TABLE cast_profile ADD COLUMN voice_ref_object_key VARCHAR(500);
