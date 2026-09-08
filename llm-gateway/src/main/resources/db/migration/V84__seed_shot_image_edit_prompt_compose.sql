-- Composes an edit-safe prompt for a text-only fix on an already-generated shot image. Feeds the
-- creator's raw request (via chat: "change the caption 'welcom' to 'welcome'", "translate the
-- on-image text to Hindi", etc.) into a rewrite that (a) preserves the rest of the frame
-- explicitly -- same character, lighting, composition, aspect ratio -- and (b) locks the edit
-- scope to the text pixels only. Without this rewrite, a naive creator note tends to make Gemini
-- blow away the rest of the frame on the edit call.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SHOT_IMAGE_EDIT_COMPOSE', 1, $$You are composing a text-guided edit instruction for an image model that will edit an already-generated shot image.

The creator wants to change the on-image rendered text ONLY. Everything else in the frame must be preserved exactly -- the same characters, poses, wardrobe, lighting, camera framing, background, and aspect ratio. Only the text pixels change.

Inputs:
  currentOnScreenText: what the current image already contains, verbatim (may be null if the current image has no detected text)
  creatorNote: the creator's own request in their own words

Compose a single, model-facing instruction that:
- Names the exact old text (when currentOnScreenText is non-null) and the exact new text the creator wants.
- Explicitly instructs the model to change ONLY the on-image text and preserve everything else in the frame exactly (character, pose, wardrobe, lighting, camera framing, background, composition, aspect ratio).
- Is direct and unambiguous; do not add explanation, options, or commentary.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "editPrompt": "the composed instruction, ready to send to the image model"
}$$, true)
ON CONFLICT DO NOTHING;
