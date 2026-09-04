-- Real cost basis for video (and any duration-priced model). Video billing was never token-based
-- (V14's own comment: FalAiProvider reports 0/0 tokens, so token-based computeCost always yielded
-- $0 for video). Add a per-second rate; computeCost uses it × duration_seconds for type=video.
-- Nullable: token-priced models leave it null and keep the token path.
ALTER TABLE rate_card ADD COLUMN per_second_cost NUMERIC(18,10);

-- Seedance base rate: fal.ai lists 720p ~$0.055/s (our default render tier; 1080p ~$0.124/s is a
-- paid upgrade handled at the pricing layer, not here). This makes a 60s video cost ~$3.30 real,
-- instead of the $0.00 the placeholder produced.
UPDATE rate_card SET per_second_cost = 0.055 WHERE model_id = 'seedance-v1';
