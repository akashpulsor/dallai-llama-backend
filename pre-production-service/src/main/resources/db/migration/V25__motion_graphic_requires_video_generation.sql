-- MOTION_GRAPHIC shots dispatch to video-generation-service too (the plan becomes the prompt,
-- reference images attach the same way PRODUCT_HERO's do) -- correcting V22's original seed,
-- which mistakenly modeled this as plan-only/never-rendered.
UPDATE shot_type_definition SET requires_video_generation = TRUE WHERE code = 'MOTION_GRAPHIC';
