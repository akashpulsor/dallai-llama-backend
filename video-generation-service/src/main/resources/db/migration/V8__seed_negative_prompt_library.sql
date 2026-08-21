-- doc §7.1: shared base library of common visual artifacts.
INSERT INTO negative_prompt_library (scope, provider_id, content, tags) VALUES
('BASE', NULL, 'extra fingers, malformed hands, warped face, distorted anatomy', ARRAY['anatomy']),
('BASE', NULL, 'blurry, low quality, oversaturated, motion blur artifacts', ARRAY['quality']),
('BASE', NULL, 'watermark, logo (if not brand), text (if not intended)', ARRAY['overlay']),
('BASE', NULL, 'duplicate faces, floating limbs, mangled features', ARRAY['anatomy']);

-- doc §7.2: Seedance-specific -- over-warps hands on close-ups.
INSERT INTO negative_prompt_library (scope, provider_id, content, tags) VALUES
('PROVIDER', 'fal.ai', 'deformed hands, extra fingers, mutated hands', ARRAY['anatomy', 'seedance']);
