-- PROMPT_COMPRESSION version 3: pin the prompt's own language to English.
--
-- Version 2 said nothing about language, so when a shot's dialogue is in Hindi the model read a
-- prompt containing Hindi and answered in Hindi -- rewriting the camera, lighting and direction
-- into Hindi along with it. A video model's prompt is English; the dialogue inside it is whatever
-- the character actually says. Those are two different things and only the second is the
-- creator's language choice.
UPDATE prompt_template SET active = false WHERE task_key = 'PROMPT_COMPRESSION' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PROMPT_COMPRESSION', 3, $$Rewrite the following video-generation prompt so it fits within {{maxLength}} characters.

LANGUAGE: Write the prompt in English, whatever language the input is in. The ONLY exceptions are quoted dialogue and quoted on-screen text: those stay exactly as written, in their original language and script, character for character. Never translate, transliterate or romanize them. Everything else -- action, camera, lighting, framing, performance, continuity, style -- is English.

This is a COMPRESSION task, not a selection task. Every specification in the input must survive in the output. You are shortening how things are said, never deciding which of them are worth saying.

Preserve, without exception:
- every named subject, character, product and place, spelled exactly as given
- every action and performance direction (expression, body language, emotion)
- every camera specification: framing, position, lens, focus, movement, support
- every lighting descriptor and mood
- every image-character specification: contrast, colour response, grain, halation, bloom, sharpness, flare
- every continuity anchor and style anchor
- every negative or prohibitive visual specification
- any dialogue or on-screen text, verbatim and in its original language

To find the space, in this order:
1. Delete filler words, hedges and redundant restatement.
2. Merge specifications that describe the same thing into one phrase.
3. Replace long descriptive clauses with the precise technical term for the same thing ("shot from below looking up" -> "low angle").
4. Drop labels and prose connectives, keeping the values.

Shape the result for {{targetModel}}: write it the way that model reads best -- a single flowing comma-separated description for models that take natural language, labelled lines for models that take structured directives. If {{targetModel}} is blank or unfamiliar, keep the input's existing shape.

If everything genuinely cannot fit even after all four steps, keep going with terser phrasing rather than omitting a specification. Only as an absolute last resort, drop from the least visually consequential end: scheduling and workflow notes first, then image-character fine detail, then exposure detail. Never drop a subject, an action, dialogue, or a negative specification.

Output only the rewritten prompt. No preamble, no commentary, no explanation of what you changed.$$, true)
ON CONFLICT DO NOTHING;
