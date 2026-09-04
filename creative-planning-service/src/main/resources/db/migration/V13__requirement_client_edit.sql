-- When the client themselves last edited this brief via the public share link (see
-- ProjectRequirementService#updateFromClient) -- surfaces as a "client updated this brief"
-- signal on the creator's own requirement list, without a full notification system.
ALTER TABLE project_requirement ADD COLUMN client_updated_at TIMESTAMPTZ;
