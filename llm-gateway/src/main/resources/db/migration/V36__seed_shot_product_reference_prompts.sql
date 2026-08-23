-- Vision analysis for pre-production-service's shot-level product-reference flow (see
-- ShotProductReferenceService) -- one Gemini multimodal call per classification, mirrors
-- creative-planning-service's REFERENCE_IMAGE_ANALYSIS task (also image-in, JSON-out).
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_PRODUCT_REFERENCE_CAST_DESCRIBE', 1, $$You are looking at a reference photo of a real person or product that will be used as the exact subject of a commercial video shot.

Describe what you see so this identity can be preserved exactly in a later generated image: appearance, distinguishing features, clothing/packaging, and anything else needed to render the same subject again.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "personDescription": "a detailed description of the person or product's appearance"
}$$, true),

('PRE_PROD_PRODUCT_REFERENCE_INSPIRATION_ANALYZE', 1, $$You are looking at a style/mood reference photo. It will NOT be used as the actual subject of a shot -- only its composition, lighting, mood, and camera style should be borrowed.

Analyze it and return STRICT JSON only, no markdown fences, no commentary:
{
  "detectedSubject": "what the photo actually shows (subject/scene), one short phrase",
  "dominantMood": "the dominant mood/feeling of the photo, one or two words",
  "cameraAngle": "the camera angle/framing used, one short phrase",
  "lightingStyle": "the lighting style used, one short phrase",
  "motion": "any implied motion or energy in the shot, one short phrase, or null if static"
}$$, true)
ON CONFLICT DO NOTHING;
