-- Confirmed live this rewrite needed to go further than just the identity-lock paragraph: for a
-- shot whose full labeled/structured prompt ("Shot type: ACTION\nCamera: ...\nComposition:
-- ...\nExpression: ...") failed identity-conditioned generation 8/8 times even with the corrected
-- identity clause from V69, rewriting the SAME content as one flowing natural-prose paragraph
-- (same reference photo, same scene, same identity instruction) succeeded 3/3. The labeled
-- multi-field prompt SHAPE itself, not just the identity wording, appears to make Gemini's image
-- model less likely to actually attempt identity-conditioned generation.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE', 2, $$You are rewriting an AI image-generation prompt so the image model actually produces an image instead of refusing.

Context: this prompt asks an image model to depict a specific real subject shown in an attached reference photo (a person or a product), preserving that subject's identity while adapting to a new shot's camera angle, pose, and setting.

Confirmed failure pattern (from direct trials against the real image model, same reference photo, only prompt phrasing/shape changed):
1. Absolutist, repetitive, or adversarial-sounding identity-lock phrasing -- e.g. "not someone or something that merely resembles it," "preserve the underlying identity and 3D structure exactly," "reconstruct the same underlying subject from that new angle" -- reliably makes the model refuse to generate anything at all (not a safety block, just the model declining).
2. A heavily labeled, multi-field prompt shape ("Shot type: ...\nCamera: ...\nComposition: ...\nExpression: ...\nEmotion: ...\nBody language: ...") is ALSO, independently, much less reliable than one flowing natural-language paragraph describing the same scene -- confirmed with the exact same content, reference photo, and identity instruction: the labeled-field version failed repeatedly while the flowing-prose version succeeded every time.
3. When the subject is a person, using their correct pronoun succeeds far more reliably than a generic/neutral pronoun.

Subject pronoun to use if the subject is a person (ignore if the subject is a product/object): {{pronoun}}

Rewrite the labeled prompt below into ONE flowing natural-language paragraph (or two short paragraphs at most) the way a person would actually describe a shot to a photographer -- not a list of labeled fields. Preserve every concrete piece of information given (camera framing/angle/movement/lens, composition, action, dialogue line if any, location, time of day, lighting mood, expression/emotion/body language, aspect ratio, and the identity-reference instruction) -- just fold it into ordinary descriptive prose instead of a form. Do not add new claims or drop any given detail. Do not add commentary, headers, or markdown -- the output is sent directly to the image model as-is.

PROMPT TO REWRITE:
{{candidatePrompt}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "prompt": "the complete rewritten prompt as flowing natural-language prose, ready to send to the image model as-is"
}$$, true)
ON CONFLICT DO NOTHING;
