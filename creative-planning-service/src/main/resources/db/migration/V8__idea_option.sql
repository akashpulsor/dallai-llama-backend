-- Generated idea options used to be entirely ephemeral (LLM output held only in the HTTP
-- response, never written down) -- a page refresh lost the list, and there was no way to tell
-- an LLM-generated option from a creator's edited version of one. This table fixes both: every
-- option generate() produces is saved (source=GENERATED), and saving an edited variant records
-- it as its own row (source=EDITED) pointing back at parent_id, so the lineage is queryable.
CREATE TABLE idea_option (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_requirement_id UUID NOT NULL,

    title VARCHAR(240) NOT NULL,
    concept TEXT,
    target_audience TEXT,
    campaign_angle TEXT,
    key_message TEXT,
    tone VARCHAR(240),

    source VARCHAR(16) NOT NULL DEFAULT 'GENERATED',
    parent_id UUID REFERENCES idea_option(id),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idea_option_requirement ON idea_option(project_requirement_id, created_at DESC);
