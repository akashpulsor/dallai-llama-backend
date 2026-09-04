-- New task: a rich, structured visual description of an already-generated image -- the "VLM
-- caption" half of a real multimodal-RAG setup (embedding alone can't answer "what color is her
-- dress"; this text description is what actually gets embedded/searched by chat-service's existing
-- text-ingestion pipeline). Used by pre-production-service's ProjectLockService to give the
-- client's review chat real, specific context about each shot's actual generated image instead of
-- just its kind label (STORYBOARD/PRODUCTION/...).
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_IMAGE_DESCRIBE', 1, $$Describe the attached image in specific, concrete detail for someone who cannot see it but needs to discuss and give feedback on it. Cover: who/what is in frame (people, products, objects), their appearance (clothing, colors, expression, pose), the setting/background, lighting and mood, camera framing, and any visible on-screen text. Be factual and specific -- name actual colors, actual objects, actual text -- not vague generalities.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "description": "a full paragraph covering everything above",
  "onScreenText": "any literal text visible in the image, or null if none"
}$$, true)
ON CONFLICT DO NOTHING;
