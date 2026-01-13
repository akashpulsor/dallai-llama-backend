-- Inbound Local
INSERT INTO rate_cards (id, rate_plan_id, metric, destination_type, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'INBOUND_CALL_MINUTES', 'LOCAL', 0.50, 'MINUTE', 60, 30
FROM rate_plans WHERE code = 'DEFAULT';

-- Outbound Mobile
INSERT INTO rate_cards (id, rate_plan_id, metric, destination_type, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'OUTBOUND_CALL_MINUTES', 'MOBILE', 0.80, 'MINUTE', 60, 30
FROM rate_plans WHERE code = 'DEFAULT';

-- Outbound Landline
INSERT INTO rate_cards (id, rate_plan_id, metric, destination_type, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'OUTBOUND_CALL_MINUTES', 'LANDLINE', 0.60, 'MINUTE', 60, 30
FROM rate_plans WHERE code = 'DEFAULT';

-- Outbound ISD
INSERT INTO rate_cards (id, rate_plan_id, metric, destination_type, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'OUTBOUND_CALL_MINUTES', 'ISD', 5.00, 'MINUTE', 60, 60
FROM rate_plans WHERE code = 'DEFAULT';

-- AI STT
INSERT INTO rate_cards (id, rate_plan_id, metric, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'AI_STT_SECONDS', 0.02, 'SECOND', 1, 0
FROM rate_plans WHERE code = 'DEFAULT';

-- AI LLM Tokens
INSERT INTO rate_cards (id, rate_plan_id, metric, rate_per_unit, unit, billing_increment, minimum_charge)
SELECT gen_random_uuid(), id, 'AI_LLM_TOKENS', 0.0001, 'TOKEN', 1, 0
FROM rate_plans WHERE code = 'DEFAULT';

-- DID Rental
INSERT INTO rate_cards (id, rate_plan_id, metric, rate_per_unit, unit)
SELECT gen_random_uuid(), id, 'DID_RENTAL', 500.00, 'MONTH'
FROM rate_plans WHERE code = 'DEFAULT';

-- Recording Storage (per GB per month)
INSERT INTO rate_cards (id, rate_plan_id, metric, rate_per_unit, unit)
SELECT gen_random_uuid(), id, 'RECORDING_STORAGE_GB', 10.00, 'GB_MONTH'
FROM rate_plans WHERE code = 'DEFAULT';