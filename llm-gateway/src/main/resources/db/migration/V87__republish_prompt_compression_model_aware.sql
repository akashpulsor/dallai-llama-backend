-- Republishes PROMPT_COMPRESSION as version 2, model-aware and explicitly non-lossy.
--
-- Version 1 said "remove filler words and redundant restatement", which is fine for a prompt that
-- is slightly over budget but has no answer for one that is far over it -- the model's only
-- remaining move is to start dropping specifications, silently and in whatever order it likes.
-- That mattered much less when video-generation-service was only composing a handful of fields;
-- now that the full shot plan reaches the prompt (cinematography, direction, performance, story
-- frame) compression is the stage that decides what survives, so it has to be told that the
-- answer is "all of it, said shorter" rather than "the first N things that fit".
--
-- {{targetModel}} is the video model the compressed prompt is headed for. Different models read
-- different shapes -- Wan takes one flowing descriptive sentence, Seedance takes labelled lines --
-- so compression is also the right place to fit the text to the reader instead of shortening a
-- structure that model does not parse. Empty string when the caller doesn't know the model, which
-- the "if blank" clause below handles.
UPDATE prompt_template SET active = false WHERE task_key = 'PROMPT_COMPRESSION' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PROMPT_COMPRESSION', 2, $$Rewrite the following video-generation prompt so it fits within {{maxLength}} characters.

This is a COMPRESSION task, not a selection task. Every specification in the input must survive in the output. You are shortening how things are said, never deciding which of them are worth saying.

Preserve, without exception:
- every named subject, character, product and place, spelled exactly as given
- every action and performance direction (expression, body language, emotion)
- every camera specification: framing, position, lens, focus, movement, support
- every lighting descriptor and mood
- every image-character specification: contrast, colour response, grain, halation, bloom, sharpness, flare
- every continuity anchor and style anchor
- every negative or prohibitive visual specification
- any dialogue or on-screen text, verbatim

To find the space, in this order:
1. Delete filler words, hedges and redundant restatement.
2. Merge specifications that describe the same thing into one phrase.
3. Replace long descriptive clauses with the precise technical term for the same thing ("shot from below looking up" -> "low angle").
4. Drop labels and prose connectives, keeping the values.

Shape the result for {{targetModel}}: write it the way that model reads best -- a single flowing comma-separated description for models that take natural language, labelled lines for models that take structured directives. If {{targetModel}} is blank or unfamiliar, keep the input's existing shape.

If everything genuinely cannot fit even after all four steps, keep going with terser phrasing rather than omitting a specification. Only as an absolute last resort, drop from the least visually consequential end: scheduling and workflow notes first, then image-character fine detail, then exposure detail. Never drop a subject, an action, dialogue, or a negative specification.

Output only the rewritten prompt. No preamble, no commentary, no explanation of what you changed.$$, true)
ON CONFLICT DO NOTHING;
