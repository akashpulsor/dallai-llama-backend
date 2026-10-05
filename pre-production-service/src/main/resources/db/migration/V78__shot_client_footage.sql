-- A shot the client supplies themselves (their own footage -- e.g. real traveller testimonials),
-- not one this platform generates. The creator tags it on the shot page; the client review page
-- marks where their footage goes and what is needed (client_footage_note). Closes the "pull shot X
-- from clip Y is a manual step later" gap left by creative-planning's project_reference_video.
ALTER TABLE shot ADD COLUMN client_footage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shot ADD COLUMN client_footage_note TEXT;
