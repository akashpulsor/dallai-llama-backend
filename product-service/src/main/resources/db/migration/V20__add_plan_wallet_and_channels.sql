-- =============================================================================
-- V15__update_plans_final.sql
--
-- TRANSPARENT PRICING with wallet credits and competitive channels.
-- Exchange rate: ₹94/USD. AI cost: ₹0.97/min (managed APIs).
--
-- WALLET CREDIT MODEL:
--   First month: plan_price + minimum_wallet_balance (wallet credit)
--   Subsequent months: plan_price only (user maintains wallet voluntarily)
--   Wallet credit is real money — user spends it on AI/outbound calls.
--   When wallet + free minutes = 0, calls stop. No debt, no surprise bills.
--
-- CHANNEL STRATEGY:
--   Competitors: Knowlarity/Exotel don't publish channel limits (fair-use).
--   Ozonetel: per-agent model (1 channel per agent effectively).
--   SIP trunk market: ₹2,500-5,000/month for 2-5 channels.
--   Our edge: explicit included channels, transparent, generous.
--   Channels = concurrent calls. 3 channels = 3 simultaneous calls.
--   Extra channels available as add-on (future: ₹299/channel/month).
--
-- MARGIN SUMMARY (worst case: 100% usage, managed APIs, ₹94/USD):
--   Lowest margin:  72% (Receptionist Pro — 500 AI mins at ₹0.97)
--   Highest margin: 95% (Basic PBX Pro — no AI cost)
--   Average margin: 84%
-- =============================================================================

-- Step 1: Columns (idempotent — may already exist)
ALTER TABLE plans ADD COLUMN IF NOT EXISTS minimum_wallet_balance NUMERIC(10,2) DEFAULT 500.00;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS included_channels INTEGER DEFAULT 1;

-- =============================================================================
-- BASIC PBX — No AI, telecom only
-- Wallet credit: ₹500 (for outbound calls at ₹1.50/min = ~333 mins)
-- Channels: generous — no AI processing load
-- Competitors: Knowlarity ₹1,999/agent, no channels disclosed
-- =============================================================================

UPDATE plans SET
    monthly_price          = 999.00,
    included_agents        = 0,
    included_minutes       = 0,
    included_channels      = 2,
    per_agent_fee          = 0.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = NULL,
    minimum_wallet_balance = 500.00
WHERE code = 'BASIC_PBX_STARTER';
-- First month: ₹999 + ₹500 = ₹1,499
-- Cost: ₹120 infra | Margin: 88%

UPDATE plans SET
    monthly_price          = 2499.00,
    included_agents        = 5,
    included_minutes       = 0,
    included_channels      = 5,
    per_agent_fee          = 299.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = NULL,
    minimum_wallet_balance = 1000.00
WHERE code = 'BASIC_PBX_PROFESSIONAL';
-- First month: ₹2,499 + ₹1,000 = ₹3,499
-- Cost: ₹120 infra | Margin: 95%

-- =============================================================================
-- VIRTUAL RECEPTIONIST — AI-heavy, low concurrency
-- Wallet credit: small (AI receptionist uses included mins mostly)
-- Channels: 1-2 (receptionist handles one call at a time typically)
-- Competitors: No equivalent AI product exists in Indian market
-- =============================================================================

UPDATE plans SET
    monthly_price          = 1499.00,
    included_agents        = 0,
    included_minutes       = 200,
    included_channels      = 1,
    per_agent_fee          = 0.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 300.00
WHERE code = 'RECEPTIONIST_STARTER';
-- First month: ₹1,499 + ₹300 = ₹1,799
-- AI cost (200 mins): ₹194 | Margin: 79%

UPDATE plans SET
    monthly_price          = 3999.00,
    included_agents        = 0,
    included_minutes       = 500,
    included_channels      = 2,
    per_agent_fee          = 0.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 500.00
WHERE code = 'RECEPTIONIST_PROFESSIONAL';
-- First month: ₹3,999 + ₹500 = ₹4,499
-- AI cost (500 mins): ₹485 | Margin: 84%

-- =============================================================================
-- CONVERSATIONAL IVR — AI-heavy, medium concurrency
-- Wallet credit: moderate (IVR calls are short but frequent)
-- Channels: 2-5 (multiple callers hit IVR simultaneously)
-- Competitors: Exotel basic IVR (no AI), Knowlarity basic IVR
-- =============================================================================

UPDATE plans SET
    monthly_price          = 1999.00,
    included_agents        = 0,
    included_minutes       = 200,
    included_channels      = 2,
    per_agent_fee          = 0.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 300.00
WHERE code = 'CONV_IVR_STARTER';
-- First month: ₹1,999 + ₹300 = ₹2,299
-- AI cost (200 mins): ₹194 | Margin: 86%

UPDATE plans SET
    monthly_price          = 4999.00,
    included_agents        = 0,
    included_minutes       = 500,
    included_channels      = 5,
    per_agent_fee          = 0.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 750.00
WHERE code = 'CONV_IVR_PROFESSIONAL';
-- First month: ₹4,999 + ₹750 = ₹5,749
-- AI cost (500 mins): ₹485 | Margin: 89%

-- =============================================================================
-- AI CONTACT CENTER — Agents + AI, high concurrency
-- Wallet credit: proportional to agents (more agents = more calls = more usage)
-- Channels: match or exceed agent count (each agent needs a channel)
-- Competitors: Knowlarity ₹1,999/agent (no AI, no channel info)
--              Ozonetel ~₹2,100/agent (no AI at base tier)
--              Exotel "scalable concurrent calling" (no limits disclosed)
-- =============================================================================

UPDATE plans SET
    monthly_price          = 2999.00,
    included_agents        = 3,
    included_minutes       = 300,
    included_channels      = 3,
    per_agent_fee          = 499.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 500.00
WHERE code = 'AI_CC_STARTER';
-- First month: ₹2,999 + ₹500 = ₹3,499
-- Per-agent: ₹999 vs Knowlarity ₹1,999 (50% cheaper + AI)
-- AI cost (300 mins): ₹291 | Margin: 83%

UPDATE plans SET
    monthly_price          = 5999.00,
    included_agents        = 5,
    included_minutes       = 500,
    included_channels      = 5,
    per_agent_fee          = 399.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 750.00
WHERE code = 'AI_CC_PROFESSIONAL';
-- First month: ₹5,999 + ₹750 = ₹6,749
-- Per-agent: ₹1,199 vs Ozonetel ₹2,100 (43% cheaper + AI)
-- AI cost (500 mins): ₹485 | Margin: 88%

UPDATE plans SET
    monthly_price          = 14999.00,
    included_agents        = 10,
    included_minutes       = 1000,
    included_channels      = 15,
    per_agent_fee          = 349.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.25,
    minimum_wallet_balance = 1500.00
WHERE code = 'AI_CC_ENTERPRISE';
-- First month: ₹14,999 + ₹1,500 = ₹16,499
-- Per-agent: ₹1,499 vs Knowlarity ₹1,999 (25% cheaper + AI)
-- 15 channels for 10 agents (1.5x ratio — headroom for queue overflow)
-- AI cost (1000 mins): ₹970 | Margin: 90%

-- =============================================================================
-- OUTBOUND DIALER — High channel usage, mixed AI + telecom
-- Wallet credit: higher (dialers burn through minutes fast)
-- Channels: generous (dialer needs multiple concurrent outbound lines)
-- Blended AI cost: 25% AI (₹0.97) + 75% telecom (₹0.50) = ₹0.62/min
-- Competitors: Knowlarity OBD (add-on), Ozonetel dialer (premium tier only)
-- =============================================================================

UPDATE plans SET
    monthly_price          = 2999.00,
    included_agents        = 3,
    included_minutes       = 300,
    included_channels      = 5,
    per_agent_fee          = 499.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 500.00
WHERE code = 'DIALER_STARTER';
-- First month: ₹2,999 + ₹500 = ₹3,499
-- 5 channels for 3 agents (1.7x ratio — dialer fires multiple lines per agent)
-- Blended cost (300 mins): ₹186 | Margin: 87%

UPDATE plans SET
    monthly_price          = 7999.00,
    included_agents        = 5,
    included_minutes       = 500,
    included_channels      = 10,
    per_agent_fee          = 399.00,
    setup_fee              = 0.00,
    ai_rate_per_min        = 1.50,
    minimum_wallet_balance = 1000.00
WHERE code = 'DIALER_PROFESSIONAL';
-- First month: ₹7,999 + ₹1,000 = ₹8,999
-- 10 channels for 5 agents (2x ratio — predictive dialer needs headroom)
-- Blended cost (500 mins): ₹310 | Margin: 92%

-- =============================================================================
-- CHANNEL COMPARISON vs COMPETITORS:
--
-- Provider        | Channels                    | Transparent?
-- Dalai LLAMA     | 1-15 included, per plan     | YES — published
-- Knowlarity      | Not disclosed (fair use)    | NO
-- Exotel          | "Scalable" (no limits shown) | NO
-- Ozonetel        | ~1 per agent (implied)      | NO
-- MyOperator      | Not disclosed               | NO
-- SIP trunk mkt   | ₹500-1,000/channel/month    | YES
--
-- Your channels are FREE and included. Competitors either hide limits
-- or charge ₹500-1,000/channel as add-ons.
-- =============================================================================

-- =============================================================================
-- WALLET CREDIT SUMMARY:
--
-- Plan                      | Wallet Credit | What it buys
-- Basic PBX Starter         | ₹500          | ~333 outbound mins at ₹1.50/min
-- Basic PBX Pro             | ₹1,000        | ~666 outbound mins
-- Receptionist Starter      | ₹300          | ~200 extra AI mins
-- Receptionist Pro          | ₹500          | ~333 extra AI mins
-- Conv IVR Starter          | ₹300          | ~200 extra AI mins
-- Conv IVR Pro              | ₹750          | ~500 extra AI mins
-- AI CC Starter             | ₹500          | ~333 extra AI mins
-- AI CC Professional        | ₹750          | ~500 extra AI mins
-- AI CC Enterprise          | ₹1,500        | ~1,200 extra AI mins at ₹1.25
-- Dialer Starter            | ₹500          | ~333 extra mins (blended)
-- Dialer Professional       | ₹1,000        | ~666 extra mins (blended)
--
-- Wallet credit is charged ONCE on first subscription.
-- It is REAL MONEY in user's wallet — used for calls beyond free minutes.
-- After wallet depletes, user tops up voluntarily (min ₹500).
-- No auto-debit, no surprise charges.
-- =============================================================================