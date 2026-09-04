-- "Which idea was this generated from" propagates the same way project_id already does: a plain
-- nullable, unenforced (no FK -- creative-planning-service owns the real row, same soft-reference
-- convention project.locked_idea_id itself already uses) column stamped at generation time.
ALTER TABLE script ADD COLUMN locked_idea_id UUID;
ALTER TABLE screenplay ADD COLUMN locked_idea_id UUID;
ALTER TABLE shot ADD COLUMN locked_idea_id UUID;

UPDATE script SET locked_idea_id = p.locked_idea_id FROM project p WHERE p.id = script.project_id;
UPDATE screenplay SET locked_idea_id = p.locked_idea_id FROM project p WHERE p.id = screenplay.project_id;
UPDATE shot SET locked_idea_id = p.locked_idea_id FROM project p WHERE p.id = shot.project_id;
