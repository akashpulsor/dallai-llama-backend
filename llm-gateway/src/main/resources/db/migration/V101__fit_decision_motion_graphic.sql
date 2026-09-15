-- VIDEO_DIALOGUE_FIT_DECISION gained a shot type it says nothing about.
--
-- The template tells the model to weigh what kind of shot it is judging -- a B-roll cutaway absorbs
-- spare seconds without anyone noticing, a line of dialogue cut into a conversation does not -- and
-- lists those cases. It never mentions a motion graphic, because when it was written motion graphics
-- could not be generated at all and so could never reach it.
--
-- They can now, and a narrator over a graphic is one of the commonest shapes in this kind of film.
-- Left unmentioned, the model has to guess which of the listed cases a motion graphic resembles, and
-- the honest answer is neither: a designed card holding two seconds longer costs nothing visually,
-- because nothing in frame is mid-movement and no performance is interrupted. Extending is usually
-- the right call there, and much more often right than it is for live action -- which is the
-- opposite of the caution the rest of the template is built around.
--
-- Updates the active row rather than adding a version. The template was only reachable from
-- 0.2.48-fit-tenant onward -- every earlier call passed no tenant and came back 401 -- so no
-- decision has ever been made with the current text, and there is no history worth preserving.

UPDATE prompt_template
SET content = replace(
        content,
        'Weigh the type of shot. A B-roll or establishing shot usually absorbs extra seconds without anyone noticing. A line of dialogue on a face, cut into a conversation, usually does not. A closing call-to-action must not lose the brand or the instruction, whatever else goes.',
        'Weigh the type of shot. A B-roll or establishing shot usually absorbs extra seconds without anyone noticing. A line of dialogue on a face, cut into a conversation, usually does not. A closing call-to-action must not lose the brand or the instruction, whatever else goes. A MOTION_GRAPHIC is the easiest of all to extend: it is a designed card, nothing in it is mid-movement and no performance is interrupted, so holding it a second or two longer costs the film nothing -- prefer EXTEND there unless the graphic is the last shot and the extra time would leave the film sitting on a static frame.')
WHERE task_key = 'VIDEO_DIALOGUE_FIT_DECISION'
  AND active = true;
