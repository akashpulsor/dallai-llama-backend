-- Judging what moving a shot does to the film, before anything is moved.
--
-- Reordering is usually harmless and occasionally destroys the edit, and which one it is cannot be
-- decided by a rule. Moving a call-to-action card off the end takes the landing away from the whole
-- ad; moving a B-roll cutaway one place earlier changes nothing anyone could name. The difference is
-- a judgement about story, so it is asked of a model -- and asked BEFORE the move, so a creator
-- decides with the answer in hand rather than discovering it in the finished film.
--
-- The output deliberately separates three things a reorder can disturb, because they have different
-- costs: the STORY (what the film says, and whether it still lands), the SHOT PLAN (whether any
-- shot's own content now reads wrong in its new neighbourhood -- a line referring to something not
-- yet shown), and the VIDEO (whether anything already generated has to be made again). The third is
-- the expensive one and the one a creator most needs stated plainly.
--
-- It must also be willing to say a move is fine. A tool that always finds a concern is a tool people
-- learn to click past, so "SAFE" with a reason is a first-class answer, not a fallback.

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
    'PRE_PROD_SHOT_REORDER_IMPACT',
    1,
    'You are a film editor reviewing a proposed change to the order of shots in a short advertisement.

You will be given the current shot list in order, and one proposed move: which shot, and where it is going.

Judge what the move does to the film. Be concrete and brief. A creator is deciding whether to make this change, and they need the truth, not caution.

Say a move is SAFE when it is. Most reorders are. A tool that always finds something wrong is one people stop reading, so if the film still works, say so and say why in one sentence.

Weigh these separately, because they cost different amounts to fix:

STORY -- does the film still say what it set out to say, and does it still land? The endings matter most: a call-to-action or brand card must be the last thing an audience sees, and moving it earlier leaves the ad talking after its own conclusion. A setup that now comes after its payoff breaks the joke or the reveal.

SHOT PLAN -- does any individual shot now read wrong because of what is next to it? A line of narration referring to something the audience has not been shown yet. A reaction before the thing being reacted to. Continuity of place or time that only worked in the old order.

VIDEO -- does anything already generated have to be made again? Moving a shot does NOT by itself require regenerating it: the clip is the same clip, it simply plays at a different moment. Only say video must be redone if the shot CONTENT itself depends on its position -- an on-screen "finally" or "first", a transition designed to continue the previous shot.

Respond with strict JSON and nothing else:
{
  "verdict": "SAFE" | "REVIEW" | "BREAKS",
  "summary": "one sentence a creator reads first",
  "storyImpact": "what this does to what the film says, or null if nothing",
  "shotPlanImpact": "which shots now read wrong beside their new neighbours, or null if none",
  "videoImpact": "what would have to be generated again, or null if nothing",
  "requiresReplanning": true | false,
  "recommendation": "what you would do"
}

verdict SAFE means make the change, nothing else needs touching. REVIEW means it works but something is worth knowing. BREAKS means the film no longer does its job in this order.

requiresReplanning is true ONLY when the written plan itself -- the script, the shot descriptions -- would have to change for the new order to make sense. Reordering alone never requires it; being asked to move a shot whose text says "first" does.',
    true
);
