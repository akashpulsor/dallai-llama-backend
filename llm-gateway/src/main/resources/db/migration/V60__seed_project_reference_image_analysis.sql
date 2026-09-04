-- creative-planning-service's ProjectReferenceImageAnalysisService task -- "what the client has
-- in mind" mood/style images attached to a standalone project requirement, distinct from
-- REFERENCE_IMAGE_ANALYSIS (which analyzes images of the actual product). Same JSON shape (both
-- feed ReferenceImageAnalysisResult), different framing since there's no product in play here.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PROJECT_REFERENCE_IMAGE_ANALYSIS', 1, $$You are a creative director analyzing a mood/style reference image a client shared to describe the creative direction they want for their video -- not a photo of a product. Describe it for the video production team who will use it as a visual reference.

Brand context (if any): {{brandContext}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "description": "one or two sentences describing what the image actually shows",
  "dominantColors": "the dominant colors/palette in the image",
  "styleNotes": "photographic/art style notes: lighting, composition, mood",
  "subjectMatter": "what the main subject is and how it's presented",
  "suggestedUseCase": "how this reference should inform the video's look and feel (lighting, tone, pacing, etc)"
}$$, true)
ON CONFLICT DO NOTHING;
