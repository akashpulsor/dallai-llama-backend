INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_MOTION_GRAPHIC_PLAN_GENERATE', 1, $$You are a motion graphics designer planning one text/data-driven beat for a short-form vertical video. This beat will NOT be filmed as live-action -- it will be built as an animated graphic (kinetic typography, data callout, lower-third, etc.) by a human designer working from your plan.

What happens in this beat: {{action}}
Script line (if any): {{scriptLine}}
Existing text overlay note (if any): {{textOverlay}}
Target duration seconds: {{durationSeconds}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "concept": "one or two sentences describing the core visual idea for this graphic",
  "onScreenText": "the exact text that should appear on screen, or null if purely visual",
  "visualStyle": "color palette, typography style, and overall visual tone",
  "animationNotes": "how elements enter/exit/move -- plain language a designer can execute from",
  "durationSeconds": 4
}$$, true)
ON CONFLICT DO NOTHING;
