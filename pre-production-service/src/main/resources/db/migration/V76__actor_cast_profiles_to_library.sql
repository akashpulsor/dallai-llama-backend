-- Actors are reusable across projects: the Cast tab used to create them project-scoped, which hid
-- them from the Cast Library and every other project. Promote those to library entries
-- (project_id IS NULL). Products stay project-scoped.
UPDATE cast_profile SET project_id = NULL WHERE profile_type = 'ACTOR' AND project_id IS NOT NULL;
