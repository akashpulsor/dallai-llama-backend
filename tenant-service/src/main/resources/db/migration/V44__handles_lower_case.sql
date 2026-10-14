-- Handles are lower case: dalaillama.in/c/Akash and /c/akash are one creator, never two. The app
-- already lower-cases every handle it stores (none in prod are mixed case); this makes the
-- database refuse one that isn't, so a case-only clash can't slip past the unique key.
ALTER TABLE creator_public_profile
    ADD CONSTRAINT creator_public_profile_handle_lower_ck CHECK (handle = lower(handle));
ALTER TABLE creator_handle_history
    ADD CONSTRAINT creator_handle_history_handle_lower_ck CHECK (handle = lower(handle));
