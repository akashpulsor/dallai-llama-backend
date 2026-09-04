-- pre-production-service's ShotImageService: identity-conditioned PRODUCTION shot images (a real
-- cast member's face, or a real product photo, attached as the reference) were failing with
-- Gemini's image model returning finishReason=IMAGE_OTHER (no image, no safety block either --
-- Gemini's own finishMessage just says "the model could not generate the image... try rephrasing
-- the prompt"). Confirmed via direct A/B trials against the real API, holding the reference image
-- and every other prompt detail fixed: the old absolutist/repetitive identity-lock wording ("not
-- someone or something that merely resembles it... preserve the underlying identity and 3D
-- structure exactly... reconstruct the same underlying subject") failed 6/6; short, natural
-- phrasing using the subject's correct pronoun succeeded 4/4, while the same wording with a
-- generic "their" failed 0/5. Rather than hand-tune one static Java string per subject-type
-- combination (person/product, known/unknown gender) and hope it generalizes, this has a text
-- model rewrite the already-assembled candidate prompt per shot -- applying the same empirically-
-- confirmed principle (natural, concrete, non-absolutist phrasing) contextually, instead of
-- re-deriving it by hand for every future combination.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE', 1, $$You are rewriting an AI image-generation prompt so the image model actually produces an image instead of refusing.

Context: this prompt asks an image model to depict a specific real subject shown in an attached reference photo (a person or a product), preserving that subject's identity while adapting to a new shot's camera angle, pose, and setting.

Confirmed failure pattern (from direct trials against the real image model, same reference photo, only the identity-instruction wording changed): absolutist, repetitive, or adversarial-sounding identity-lock phrasing -- e.g. "not someone or something that merely resembles it," "preserve the underlying identity and 3D structure exactly," "reconstruct the same underlying subject from that new angle" -- reliably makes the model refuse to generate anything at all, even though this is not a safety block, just the model declining. Short, plain, ordinary phrasing about the subject succeeds far more often. When the subject is a person, using their correct pronoun succeeds much more reliably than a generic/neutral pronoun -- confirmed the identical wording with "their" instead of the correct pronoun failed consistently where the correct pronoun succeeded.

Subject pronoun to use if the subject is a person (ignore if the subject is a product/object): {{pronoun}}

Rewrite the prompt below with that in mind: keep every concrete shot detail exactly as given (camera, composition, action, dialogue line, lighting, location, aspect ratio, expression/emotion/body language) -- only change the TONE of the identity-instruction portion to be natural and concrete rather than exhaustive or absolutist. Do not add new claims or drop any given detail. Do not add commentary, headers, or markdown -- the output is sent directly to the image model as-is.

PROMPT TO REWRITE:
{{candidatePrompt}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "prompt": "the complete rewritten prompt, ready to send to the image model as-is"
}$$, true)
ON CONFLICT DO NOTHING;
