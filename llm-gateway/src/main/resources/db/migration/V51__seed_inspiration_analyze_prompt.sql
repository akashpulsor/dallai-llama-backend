-- "Pick a reference photo (e.g. from Pinterest), figure out its cinematic technique, apply that
-- technique to our own shot" -- this is the analysis half. Deliberately NOT a general "what's in
-- this image" description (that's PRE_PROD_IMAGE_DESCRIBE) -- this extracts the *directorial*
-- qualities worth copying: lighting, camera angle/framing, composition/motion, texture/material,
-- color grade, mood. Consumed by ShotImageDescriptionService.analyzeInspiration(), folded into the
-- next shot-image generation call as explicit style direction alongside the raw reference photo.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_INSPIRATION_ANALYZE', 1, $$You are a cinematographer studying a reference photo to direct a new shot in its style. Look at the attached image and describe, in concrete technical terms, ONLY the cinematic technique -- not the subject matter (never suggest copying the people/objects/branding in it, only the craft).

Return STRICT JSON only, no markdown fences, no commentary:
{
  "lighting": "light quality, direction, hardness/softness, key/fill/rim setup, color temperature",
  "cameraAngle": "shot size, angle (high/low/eye-level), lens feel (wide/telephoto/macro), depth of field",
  "composition": "framing, rule-of-thirds/centering, motion or implied motion, negative space",
  "texture": "surface qualities, grain, material rendering, sharpness/softness",
  "colorGrade": "palette, contrast, saturation, overall tonal mood",
  "mood": "one short phrase for the overall emotional register"
}$$, true)
ON CONFLICT DO NOTHING;
