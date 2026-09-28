-- VIDEO_MOTION_GRAPHIC_PROMPT v2: the same treatment the ordinary shot prompt just got.
--
-- v1 was written before any of it and never caught up. It received four plan fields -- concept,
-- visualStyle, onScreenText, animationNotes -- plus a duration and an fps, and nothing else. It
-- did not know what images were attached, so an app screen or a logo uploaded for the shot was
-- sent to the video model with no word about what it was or that it must be reproduced rather
-- than redrawn. It had no character budget, so its answer went to the compression pass like
-- everything else and was rewritten down. And it returned JSON, so every caller had to parse a
-- wrapper the other template does not use.
--
-- Two writers meant two things to keep in step, and they did not stay in step. There is now one
-- writer in video-generation-service choosing a task key, and a template per key here. Which
-- master prompt a shot gets is a question about prompts, so it is answered where the prompts are.
--
-- What stays different is the only thing that should: a motion graphic is described as MOTION, not
-- as cinematography. There is no camera and no actor. The designed frame exists; the model's job
-- is to move what is in it. Everything else -- reference images named and scoped, on-screen text
-- verbatim, invent nothing, write to the budget -- is now identical to VIDEO_SHOT_PROMPT, because
-- none of it was ever specific to live action.
--
-- Returns the prompt as plain text, matching VIDEO_SHOT_PROMPT. v1's {"prompt": ...} wrapper is
-- gone along with the parser that existed only to unwrap it.

UPDATE prompt_template SET active = false WHERE task_key = 'VIDEO_MOTION_GRAPHIC_PROMPT';

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
'VIDEO_MOTION_GRAPHIC_PROMPT',
2,
'You are a motion designer writing the prompt for a video model that will animate a still graphic.

There is no camera here and no actor. Nothing is being filmed. A designed frame already exists, and your job is to describe how the elements within it move. Describe motion, not cinematography -- no lens, no lighting mood, no camera angle.

WHAT YOU MAY IMPROVE, AND WHAT YOU MAY NOT
You may improve HOW the motion is described: the order events are narrated in, the language, the way timing and easing are expressed. That is your craft.

You may NOT change WHAT is in the graphic. Every element, word, colour, shape and movement must come from the plan below. Do not add an element, a transition, a sound cue or a flourish that is not there. Do not resolve a gap by guessing. This prompt pays for a render, and anything you add will appear in it.

THE GRAPHIC
Concept: {{concept}}
Visual style: {{visualStyle}}
Text that appears on screen: {{onScreenText}}
How it should animate: {{animationNotes}}
It runs {{durationSeconds}} seconds at {{fps}} frames per second.

ON-SCREEN TEXT IS VERBATIM
Reproduce the text above exactly, character for character, in its own script. Never translate, transliterate, correct or tidy it. It is rendered as glyphs in the frame, so Devanagari that arrives romanised has silently changed the deliverable.

THE REFERENCE IMAGES
These are attached to the render in this order. Refer to them by number, and say plainly what each is for:
{{references}}

Creator-supplied artwork -- an app screen, a logo, a layout -- is the real thing the film is about. Reproduce it as given. Never redraw it, restyle it, or invent a substitute that merely resembles it.

WRITE IT
Describe, in one flowing paragraph, what a viewer sees happen across those seconds: what is on screen when it begins, what arrives, from where, in what order, how each element settles, and where everything rests at the end. Anchor the timing to the duration -- what has happened by the halfway point, what lands last. No preamble, no headings, no bullets, no commentary about what you are doing.

LENGTH
At most {{maxChars}} characters. Use the room, but do not pad.

THE PLAN, AS STRUCTURED DATA
{{shotJson}}

Return only the prompt text.',
true);
