CREATE EXTENSION IF NOT EXISTS vector;

-- RAG index for the storyboard workspace chat "brain". One row per
-- retrievable unit of context (a shot, a generated shot image's caption, or
-- script metadata). Retrieval is scoped to a single workspace so the index
-- stays small and cheap to query - this is not a cross-project index.
CREATE TABLE creator_storyboard_context_chunks
(
    id                      UUID PRIMARY KEY,

    workspace_id            UUID NOT NULL,

    -- SHOT, SHOT_IMAGE, SCRIPT_META
    chunk_type              VARCHAR(32) NOT NULL,

    -- Shot number for shot-scoped chunks, or a fixed ref for SCRIPT_META.
    ref_id                  VARCHAR(64),

    content                 TEXT NOT NULL,

    token_count             INTEGER NOT NULL DEFAULT 0,

    -- text-embedding-004 output dimensionality. See CreatorProperties.ai.geminiEmbeddingDimensions.
    embedding               vector(768) NOT NULL,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_storyboard_context_chunk_workspace
        FOREIGN KEY (workspace_id)
            REFERENCES creator_storyboard_workspaces(id)
            ON DELETE CASCADE,

    -- At most one live chunk per (workspace, type, ref) - re-indexing upserts.
    CONSTRAINT uq_storyboard_context_chunk_ref
        UNIQUE (workspace_id, chunk_type, ref_id)
);

CREATE INDEX idx_storyboard_context_chunk_workspace
    ON creator_storyboard_context_chunks (workspace_id);

CREATE INDEX idx_storyboard_context_chunk_type
    ON creator_storyboard_context_chunks (workspace_id, chunk_type);

-- Cosine similarity ANN index; ivfflat keeps build cost low at this table's
-- expected size (a few hundred chunks per workspace at most).
CREATE INDEX idx_storyboard_context_chunk_embedding
    ON creator_storyboard_context_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
