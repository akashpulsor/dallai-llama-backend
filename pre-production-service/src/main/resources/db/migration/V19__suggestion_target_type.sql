-- Master/reference table for what a client's chat suggestion can target -- chat-service reads
-- this catalog (GET /api/v1/internal/suggestion-target-types) rather than carrying its own
-- hardcoded copy of "which target types exist".
CREATE TABLE suggestion_target_type (
    code VARCHAR(32) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    description TEXT NOT NULL,
    requires_target_ref BOOLEAN NOT NULL,
    target_ref_hint VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO suggestion_target_type (code, label, description, requires_target_ref, target_ref_hint, active) VALUES
('SCRIPT', 'Script', 'The project''s script text, pacing, hook, and emotional arc.', FALSE, NULL, TRUE),
('SCREENPLAY', 'Screenplay', 'The scene-by-scene screenplay breakdown.', FALSE, NULL, TRUE),
('SHOT_IMAGE', 'Shot image', 'One generated image (storyboard, production, lighting, or camera-plan) for a specific shot.', TRUE, '<shotRef>:<imageKind> using the exact shotRef from the retrieved context, e.g. "shot-02-004:PRODUCTION"', TRUE);
