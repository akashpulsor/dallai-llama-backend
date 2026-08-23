CREATE TABLE shot_type_definition (
    code VARCHAR(32) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    description TEXT NOT NULL,
    requires_video_generation BOOLEAN NOT NULL,
    requires_motion_graphics BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO shot_type_definition (code, label, description, requires_video_generation, requires_motion_graphics, active) VALUES
('DIALOGUE', 'Dialogue', 'A character speaks on camera.', TRUE, FALSE, TRUE),
('ACTION', 'Action', 'Physical action or movement, no dialogue.', TRUE, FALSE, TRUE),
('PRODUCT_HERO', 'Product hero', 'The product is the focal subject of the shot.', TRUE, FALSE, TRUE),
('B_ROLL', 'B-roll', 'Supporting footage, no primary character or product focus.', TRUE, FALSE, TRUE),
('TRANSITION', 'Transition', 'A cut/wipe/motion bridge between two other shots.', TRUE, FALSE, TRUE),
('MOTION_GRAPHIC', 'Motion graphic', 'Text/data/graphic-driven beat -- planned, not generated as live-action video.', FALSE, TRUE, TRUE);

CREATE TABLE video_feature_flag_definition (
    flag_key VARCHAR(32) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    description TEXT NOT NULL,
    default_enabled BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO video_feature_flag_definition (flag_key, label, description, default_enabled, active) VALUES
('dialogue', 'Dialogue / voice-over', 'Include the shot''s dialogue or voice-over line in the generated video.', TRUE, TRUE),
('captions', 'On-screen captions', 'Allow the model to render on-screen text/captions where it would naturally do so.', FALSE, TRUE);

CREATE TABLE aspect_ratio_option (
    code VARCHAR(32) PRIMARY KEY,
    label VARCHAR(40) NOT NULL,
    orientation VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO aspect_ratio_option (code, label, orientation, active) VALUES
('RATIO_16_9', '16:9 Widescreen', 'HORIZONTAL', TRUE),
('RATIO_21_9', '21:9 Cinematic', 'HORIZONTAL', TRUE),
('RATIO_9_16', '9:16 Vertical', 'VERTICAL', TRUE),
('RATIO_4_5', '4:5 Portrait', 'VERTICAL', TRUE),
('RATIO_1_1', '1:1 Square', 'SQUARE', TRUE);
