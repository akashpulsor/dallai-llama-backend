ALTER TABLE idea_option
    ADD COLUMN critic_verdict VARCHAR(16),
    ADD COLUMN completeness_score INT,
    ADD COLUMN story_score INT,
    ADD COLUMN distinctiveness_score INT,
    ADD COLUMN critic_strengths TEXT,
    ADD COLUMN critic_concerns TEXT;
