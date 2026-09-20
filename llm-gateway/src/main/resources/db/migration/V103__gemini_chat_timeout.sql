-- THE SHOT LIST NEEDS LONGER THAN 90 SECONDS, MEASURED.
--
-- Shot-list generation failed three times for one project, each attempt cut off at exactly 90s with
-- nothing to show: status FAILED, result_len 0, cost 0. It looked like Gemini was broken or the
-- request was too large. It was neither -- replaying the exact stored request against Gemini
-- returned a complete answer:
--
--   finishReason          STOP        (not MAX_TOKENS -- nothing was truncated)
--   candidatesTokenCount  16703       (the shot list itself)
--   thoughtsTokenCount    6145        (2.5-flash reasons before answering, and that is billed time)
--   totalTokenCount       26199
--   response              74 KB of valid JSON
--
-- So it simply takes longer than 90s to write ~23k tokens. The request was never the problem: 13k
-- characters of prompt against a model that accepts a million tokens.
--
-- This is the timeout that actually governs. llm-gateway.google.timeout-ms in application.yml is
-- only the fallback for a model row that does not carry one -- LlmGatewayService passes
-- routed.model().getTimeoutMs() into the provider, so raising the config default changed nothing
-- and the fourth attempt failed at 90000ms on the build that supposedly fixed it.
--
-- 300s, not 180s: ~23k tokens at Flash's observed rate leaves 180 uncomfortably close to the line,
-- and the caller here is a Kafka consumer with nobody waiting on it. A synchronous caller is
-- unaffected -- its own 120s budget still expires first, so this cannot make any page slower.
UPDATE model_master
SET timeout_ms = 300000
WHERE model_id = 'gemini-2.5-flash'
  AND type = 'chat'
  AND timeout_ms < 300000;
