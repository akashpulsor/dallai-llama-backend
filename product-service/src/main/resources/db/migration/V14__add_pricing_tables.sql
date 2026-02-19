-- Updated pricing (more competitive + sustainable)

-- STARTER (Budget AI)
UPDATE plans SET
    monthly_price = 2999,      -- Platform fee (was ?)
    per_agent_fee = 499,       -- Lower barrier (was 699)
    included_agents = 3,
    included_minutes = 1000,   -- Reduced (was 3000) - ₹4,000 value
    ai_stack_type = 'BUDGET',
    ai_rate_per_min = 3.50     -- More competitive (was 4.00)
WHERE code = 'AI_CC_STARTER';

-- PROFESSIONAL (Standard AI)
UPDATE plans SET
    monthly_price = 7999,      -- Platform fee
    per_agent_fee = 599,       -- (was 699)
    included_agents = 5,
    included_minutes = 2000,   -- Reduced (was 3000) - ₹11,000 value
    ai_stack_type = 'STANDARD',
    ai_rate_per_min = 5.50     -- More competitive (was 6.00)
WHERE code = 'AI_CC_PROFESSIONAL';

-- ENTERPRISE (Premium AI)
UPDATE plans SET
    monthly_price = 24999,     -- Platform fee (was 49999 - too high!)
    per_agent_fee = 499,       -- Lower for volume (was 599)
    included_agents = 10,
    included_minutes = 3000,   -- Reduced (was 5000) - ₹42,000 value
    ai_stack_type = 'PREMIUM',
    ai_rate_per_min = 14.00    -- Higher margin (was 12.00)
WHERE code = 'AI_CC_ENTERPRISE';