-- VIDEO_DIALOGUE_FIT_DECISION: when a shot's line runs longer than the clip planned for it, decide
-- which of the three remedies suits THIS shot -- give it more seconds, rewrite the line, or generate
-- it as planned and accept the clipped tail.
--
-- The arithmetic cannot answer this. It can say that shot-01-005 carries 12.21 seconds of narration
-- in a 5-second clip and that closing the gap costs 2.6x, which are facts, and it can bound what is
-- possible. What it cannot weigh is whether eight more seconds of a B-roll cutaway would still look
-- like the film, whether a closing call-to-action can lose words without losing the brand, or
-- whether this particular shot is the one carrying the scene's point. Those are judgements about
-- craft, and a threshold of "extend by at most two seconds" answers them all identically and is
-- therefore wrong about most of them.
--
-- What this prompt is NOT asked is how long anything takes. That was tried in V97 and failed badly:
-- asked about shot-01-003 it judged "about eighteen" seconds for a line whose synthesized take runs
-- 4.32. Every duration here is measured with ffprobe and handed to the model as fact. It is asked to
-- choose between options, given numbers, which is a different and far more reliable kind of question.
--
-- Only runs when there is a mismatch. A shot whose line fits is generated without ever reaching a
-- model -- the check upstream is arithmetic against measured audio, and it is free.
--
-- Advisory. The recommendation is shown to the creator as the suggested option with its reasoning,
-- and they choose. The returned duration is clamped to what the model can actually generate before
-- anything is done with it: a recommendation is a judgement, not an authorisation to spend.

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
'VIDEO_DIALOGUE_FIT_DECISION',
1,
'You are an editor deciding what to do about a shot whose dialogue runs longer than the shot itself.

THE SHOT
Reference: {{shotRef}}
Type: {{shotType}}
What happens on screen: {{action}}
Planned length: {{plannedSeconds}} seconds at {{fps}} frames per second
The line: {{dialogue}}

THE MEASUREMENT
The line has been recorded. Speaking it takes {{measuredSeconds}} seconds -- measured from the audio, not estimated, so treat it as fact. Allowing a breath at the end, the shot would need {{requiredSeconds}} seconds to contain it. It overruns by {{overrunSeconds}} seconds.

If nothing changes, the line is cut off where the clip ends. It is the END that is lost, not the beginning.

WHAT IS POSSIBLE
This shot could be extended to at most {{maxDurationSeconds}} seconds. Beyond that the model will not generate it.
Clips are billed per second: taking it to {{requiredSecondsRounded}} seconds would cost about {{costMultiple}} times what this shot costs now, and adds those seconds to the film''s total running time, which the client has agreed.

CHOOSE ONE
- EXTEND: give the shot more seconds so the whole line is heard. Right when the extra time still looks like the film -- a held shot, a cutaway, an establishing beat that can breathe -- and when the line cannot afford to lose anything.
- REWRITE: keep the shot''s length and say the same thing in less time. Right when the shot is cut to a rhythm, when the extra seconds would be dead screen time, or when the line has room to lose words without losing its point. Costs nothing extra.
- KEEP: generate exactly as planned and accept that the end of the line is cut. Right only when what gets cut does not matter -- a trailing clause, a repetition, a name already said.

Weigh the type of shot. A B-roll or establishing shot usually absorbs extra seconds without anyone noticing. A line of dialogue on a face, cut into a conversation, usually does not. A closing call-to-action must not lose the brand or the instruction, whatever else goes.

Answer with a single JSON object and nothing else:

{"recommendation": <"EXTEND", "REWRITE" or "KEEP">, "recommendedDurationSeconds": <whole seconds, only when recommending EXTEND, otherwise null>, "reason": <one sentence, addressed to the person making the film, saying why this is the right trade for this shot>}',
true);
