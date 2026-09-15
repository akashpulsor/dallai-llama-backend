-- VIDEO_MOTION_GRAPHIC_PROMPT: write the video prompt for a motion-graphic shot.
--
-- A motion graphic was being described to the video model in the vocabulary of live action. The
-- prompt built for shot-01-007 -- an interface reveal with no camera, no cast and no location --
-- was assembled from camera angle, lens, lighting mood and three characters' continuity notes, and
-- said nothing at all about what moves. The one field that does say, animationNotes, was not even
-- carried into the request.
--
-- The fix is not a bigger template. A template joins fields; it cannot read "the screen transitions
-- smoothly to reveal a stylised report card" and turn that into a description of what the video
-- model should do to the still it has been handed. That is writing, so it is asked for.
--
-- The model is given the still's plan and asked to describe the shot as MOTION: which element is
-- on screen at the start, what arrives, from where, in what order, how it settles. It is told the
-- duration so the described motion fits the seconds available, and told plainly that there is no
-- camera and no actor, which is what stopped the old prompt reading like a live-action brief.
--
-- On-screen text is passed through verbatim and must come back verbatim, in its own script. It is
-- rendered as glyphs in the frame: translating or transliterating it puts different words on screen
-- than the film intends, and Devanagari that arrives romanised has silently changed the deliverable.
--
-- Only motion-graphic shots reach this. Every other shot type composes its prompt exactly as it
-- always has -- the branch is one null check on the shot context.

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
'VIDEO_MOTION_GRAPHIC_PROMPT',
1,
'You are writing the prompt for a video model that will animate a still graphic.

There is no camera here and no actor. Nothing is being filmed. A designed frame already exists, and the model''s job is to move the elements within it. Describe motion, not cinematography.

THE GRAPHIC
Concept: {{concept}}
Visual style: {{visualStyle}}
Text that appears on screen: {{onScreenText}}
How it should animate: {{animationNotes}}
This shot runs {{durationSeconds}} seconds at {{fps}} frames per second.

WRITE THE PROMPT
Describe, in one flowing paragraph, what the viewer sees happen over those seconds:
- What is on screen when the shot begins.
- Each element that arrives: what it is, where it comes from, and when relative to the others. Name them in the order they land.
- How each one moves -- fades up, wipes across, slides in from an edge, scales up, settles.
- Where everything has come to rest by the end of the shot.

Keep it to motion the time allows. A three-second shot holds two or three beats of animation, not eight; if the plan asks for more than fits, describe the ones that carry the idea and let the rest go.

End with the graphic on screen, complete and at rest. Do NOT animate an exit -- no fade out, no scale down, no dissolve at the end -- even where the plan mentions one. In an edit the cut ends the shot, so animating the graphic away spends the last of the seconds removing what the shot exists to show, and leaves the final frames empty for the next shot to cut from. A plan''s "exit" note describes how it leaves the FILM, which the edit handles, not something this clip should perform.

Reproduce {{onScreenText}} EXACTLY as it is written above -- same characters, same script, same spelling. It is rendered as glyphs on screen, so translating it, transliterating it, or "correcting" it puts different words in the film than were intended. If it is in Devanagari it stays in Devanagari.

Do not write camera directions -- no angle, no lens, no dolly, no pan -- unless the plan itself asks the whole frame to move. Do not describe people, rooms, lighting setups or wardrobe: none of those are in this shot. Do not add elements the plan does not mention.

Answer with a single JSON object and nothing else:

{"prompt": <the paragraph described above>}',
true);
