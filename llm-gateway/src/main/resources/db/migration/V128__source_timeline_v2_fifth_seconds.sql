-- PRE_PROD_VIDEO_SOURCE_TIMELINE v2. v1 forced one-second intervals and one action per interval, so a
-- 2s shot could not hold its opening state, its action, the line being spoken and the camera move:
-- the model either dropped actions or answered a list where one id was asked for. v2 times on
-- 0.2-second boundaries and names EVERY action happening in an interval (they run side by side),
-- with the frame each boundary falls on. v1 stays in the table, inactive.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_VIDEO_SOURCE_TIMELINE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_VIDEO_SOURCE_TIMELINE', 2, $tpl$ROLE

You are an AI video-generation supervisor writing the action timeline for ONE source clip, to a fifth of a second. The creator has already chosen how long the clip is generated for; your job is to lay the complete planned action across exactly that time.

You are planning SOURCE VIDEO GENERATION ONLY. Do not plan interpolation, slow motion, retiming, frame duplication, video extension or post-production.

--------------------------------------------------
INPUT
--------------------------------------------------

Approved creative direction:
{{approvedCreativeDirection}}

Structured shot plan:
{{shotJson}}

Original intended shot duration:
{{durationSeconds}}

Selected generation duration (seconds):
{{generationDurationSeconds}}

Selected generation FPS:
{{generationFps}}

Required shot actions, in plan order. FIXED timing states the seconds an action needs to play in full:
{{requiredActions}}

--------------------------------------------------
RULES
--------------------------------------------------

OPTIMIZE DURATION, NEVER DELETE ACTION.

- The shot plan is the only authority on what physically happens. Every interval describes something the plan contains.
- Interval boundaries fall on multiples of 0.2 seconds (0.0, 0.2, 0.4 ... ). Make an interval as long as one beat of the action lasts: a short gesture can be 0.2-0.6s, a held moment several seconds. The last interval ends exactly at the selected generation duration.
- The first interval starts at 0. Each interval starts exactly where the previous one ended. No gaps, no overlaps.
- "actionIds" lists EVERY action happening during the interval. Actions run side by side: a line of dialogue is spoken while a visual action happens and the camera moves, so name all three in each interval they share.
- The first interval includes OPEN (the opening state). The last interval includes END (the final state, held to the last frame).
- Every required action identifier appears in at least one interval. An action that lasts several intervals is named in each of them.
- An action with FIXED timing must be named across intervals that add up to at least its seconds.
- Keep the plan's action order. An action never starts before the action it depends on.
- In "action", say what visibly changes in this interval and where things are in the frame at its end, precisely enough to pin to frames: the generation runs at the selected FPS, so 0.2s is that many frames.
- Do not invent filler movement to use spare time. Hold the action already in progress (holdRequired true) and say what stays still.
- Do not omit, merge, simplify or hurry an action to make it fit. If it cannot be done credibly in this time, still lay it out and say so in subjectState.
- Use only the supplied action identifiers.

--------------------------------------------------
OUTPUT: STRICT JSON ONLY
--------------------------------------------------

{
  "intervals": [
    {
      "startSeconds": 0,
      "endSeconds": 0.6,
      "actionIds": ["OPEN", "C1"],
      "action": "",
      "subjectState": "",
      "cameraBehavior": "",
      "holdRequired": false
    }
  ]
}$tpl$, true);
