-- Confirmed live: lighting_plan generation failed on "value too long for type character
-- varying(200)" on a gear-placement field -- the prompt asks for "specific gear + placement"
-- (a full sentence), and the length cap was an arbitrary legacy choice, not a real business rule
-- (every comparable free-text field on these two tables -- cinematic_intent, build_steps,
-- blocking_map, execution_steps, safety_flags, compliance_note -- is already unbounded text).
-- Widens every remaining short varchar on both tables to text so this class of failure can't
-- recur as prompts grow more detailed (e.g. now folding in reference-image analysis).
ALTER TABLE lighting_plan ALTER COLUMN key_light_gear TYPE TEXT;
ALTER TABLE lighting_plan ALTER COLUMN fill_light_gear TYPE TEXT;
ALTER TABLE lighting_plan ALTER COLUMN rim_light_gear TYPE TEXT;
ALTER TABLE lighting_plan ALTER COLUMN neg_fill_gear TYPE TEXT;
ALTER TABLE lighting_plan ALTER COLUMN diffuser_gear TYPE TEXT;
ALTER TABLE lighting_plan ALTER COLUMN camera_rig_gear TYPE TEXT;

ALTER TABLE camera_plan ALTER COLUMN gimbal_device TYPE TEXT;
ALTER TABLE camera_plan ALTER COLUMN gimbal_mode TYPE TEXT;
ALTER TABLE camera_plan ALTER COLUMN gimbal_pan_speed TYPE TEXT;
ALTER TABLE camera_plan ALTER COLUMN gimbal_tilt_speed TYPE TEXT;
