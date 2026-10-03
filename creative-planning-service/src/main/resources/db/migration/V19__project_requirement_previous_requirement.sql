-- The brief a client starts from a locked video's review page ("Start your next brief").
--
-- A client who has just paid for and locked a video starts their next one from the same review
-- page; the new brief is pre-filled from the one before it and points back to it here. The unique
-- index makes that one-to-one, which is what lets a double-click or a retried request return the
-- brief already started instead of opening a second one.
--
-- NULL for every brief a creator starts themselves, which is every existing row.
ALTER TABLE project_requirement ADD COLUMN previous_requirement_id UUID REFERENCES project_requirement (id);

CREATE UNIQUE INDEX uq_project_requirement_previous ON project_requirement (previous_requirement_id)
    WHERE previous_requirement_id IS NOT NULL;
