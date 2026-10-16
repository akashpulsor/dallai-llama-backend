-- V132 guessed $0.015 an output second for topaz/interpolate/video. fal's own page (checked
-- 2026-10-16) prices slow motion on the FULL output duration -- "slow motion uses the current
-- full-output-duration calculation" -- at $0.30 per 10s of output at 1080p for Apollo/Chronos
-- (we run Chronos). That is $0.03 an output second. fal lists no cheaper tier below 1080p, so a
-- 480p clip is priced the same. Never under-price a provider call: billing debits from this.
UPDATE rate_card SET per_second_cost = 0.03 WHERE model_id = 'topaz/interpolate/video';
