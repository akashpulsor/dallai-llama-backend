-- llm_job already persists result_content (the answer) but never the request that produced it --
-- debugging a malformed response (e.g. a shot-list-generate response with a field the model
-- returned in the wrong shape) meant the raw output was inspectable but the exact rendered prompt
-- that provoked it wasn't. Nullable: only newly-dispatched jobs record it going forward, existing
-- rows just have nothing to show for "what did we send" the way they already have nothing for
-- calls made before result_content existed.
ALTER TABLE llm_job ADD COLUMN request_content TEXT;
