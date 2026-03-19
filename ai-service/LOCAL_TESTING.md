# voice-brain Local Testing Guide

## Option 1: Browser Test (No FreeSWITCH needed)

### Prerequisites
```bash
# API keys (get from respective providers)
export VB_OPENAI_API_KEY=your-openai-api-key
export VB_DEEPGRAM_API_KEY=your-deepgram-api-key

# Optional: point to local PBX-Core (if running)
export VB_PBX_CORE_URL=http://localhost:8080
```

### Start voice-brain
```bash
cd voice-brain
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8601
```

### Open browser test console
```
http://localhost:8601/test
```

1. Select product: **CONV_IVR** (full bot conversation)
2. Click **Start**
3. Allow microphone access
4. Talk — bot responds in ~1 second
5. See live transcript + intent detection in the console

### What happens without PBX-Core
voice-brain falls back to defaults:
- Default bot: "You are a helpful assistant"
- Default greeting: "Hello, how can I help you?"
- No escalation intents (threshold checks skipped)
- No RAG documents
- Transcript callbacks silently fail (logged as warnings)

---

## Option 2: With local PBX-Core (full flow)

### Start PBX-Core
```bash
cd pbx-core
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# Runs on :8080, connects to local PostgreSQL + Redis
```

### Seed test data
```sql
-- Insert a test bot
INSERT INTO bots (id, tenant_id, subscription_id, name, status,
    system_prompt, greeting_message, goodbye_message, language)
VALUES (
    'aaaaaaaa-0000-0000-0000-000000000001',
    'tttttttt-0000-0000-0000-000000000001',
    'ssssssss-0000-0000-0000-000000000001',
    'Test Sales Bot', 'ACTIVE',
    'You are a sales agent for Acme Corp. You sell cloud communication products. Be friendly and helpful. Ask qualifying questions about their current phone system.',
    'Hello! This is Priya from Acme Corp. How can I help you today?',
    'Thank you for your time. Have a great day!',
    'en'
);

-- Add escalation intents
INSERT INTO bot_escalation_intents (id, bot_id, tenant_id, intent_name, threshold, action, target, description)
VALUES
    (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'tttttttt-0000-0000-0000-000000000001',
     'interested', 0.85, 'TRANSFER_QUEUE', 'sales', 'Caller shows buying interest'),
    (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'tttttttt-0000-0000-0000-000000000001',
     'not_interested', 0.95, 'HANGUP', NULL, 'Caller firmly not interested');

-- Add knowledge docs (RAG)
INSERT INTO bot_knowledge_documents (id, bot_id, tenant_id, title, content, content_type)
VALUES
    (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'tttttttt-0000-0000-0000-000000000001',
     'Pricing', 'Basic Plan: Rs 999/month (5 agents, 10 channels). Pro Plan: Rs 2999/month (20 agents, 50 channels). Enterprise: Custom pricing.', 'PRODUCT'),
    (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'tttttttt-0000-0000-0000-000000000001',
     'Features', 'All plans include: IVR, call recording, live dashboard. Pro adds: AI transcription, sentiment analysis. Enterprise adds: custom integrations, dedicated infrastructure.', 'FAQ');

-- Warm the tenant config cache (minimal)
INSERT INTO tenant_config_cache (id, tenant_id, config_key, config_value, updated_at)
VALUES (gen_random_uuid(), 'tttttttt-0000-0000-0000-000000000001', 'productCode', 'CONV_IVR', NOW()),
       (gen_random_uuid(), 'tttttttt-0000-0000-0000-000000000001', 'aiBotEnabled', 'true', NOW()),
       (gen_random_uuid(), 'tttttttt-0000-0000-0000-000000000001', 'aiTranscriptionEnabled', 'true', NOW()),
       (gen_random_uuid(), 'tttttttt-0000-0000-0000-000000000001', 'aiSentimentEnabled', 'true', NOW());
```

### Start voice-brain pointing to local PBX-Core
```bash
VB_OPENAI_API_KEY=sk-... \
VB_DEEPGRAM_API_KEY=... \
VB_PBX_CORE_URL=http://localhost:8080 \
python -m uvicorn main:app --host 0.0.0.0 --port 8601
```

### Test in browser
```
http://localhost:8601/test
```
Set Tenant ID to: `tttttttt-0000-0000-0000-000000000001`

Now the bot will:
- Greet as "Priya from Acme Corp"
- Answer pricing questions from RAG docs
- Detect "interested" intent and escalate at 85% confidence
- Track sentiment in real-time

---

## Option 3: With FreeSWITCH (production-like)

### FreeSWITCH dialplan
Add to `/etc/freeswitch/dialplan/default.xml`:
```xml
<extension name="test-voice-brain">
  <condition field="destination_number" expression="^9999$">
    <action application="answer"/>
    <action application="set" data="sip_h_X-Tenant-ID=tttttttt-0000-0000-0000-000000000001"/>
    <action application="set" data="sip_h_X-Product-Code=CONV_IVR"/>
    <action application="socket"
            data="ws://127.0.0.1:8601/audio/${uuid}?tenant_id=tttttttt-0000-0000-0000-000000000001&amp;product_code=CONV_IVR"/>
  </condition>
</extension>
```

### Test with softphone
1. Register a SIP softphone to FreeSWITCH (e.g., Opal, MicroSIP)
2. Dial **9999**
3. FreeSWITCH connects to voice-brain
4. Talk to the bot

---

## Option 4: curl test (verify PBX-Core config endpoint)

```bash
# Check if PBX-Core returns AI config
curl -s http://localhost:8080/internal/ai/config/tttttttt-0000-0000-0000-000000000001 | python -m json.tool

# Expected response includes:
# - ai_enabled: true
# - bot.system_prompt (with RAG docs appended)
# - bot.escalation_intents (threshold + action + target)
# - bot.knowledge_documents (title + content)
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| "No AI config" warning | PBX-Core not running or tenant not found | Start PBX-Core or use browser test (falls back to defaults) |
| No audio response | TTS API key invalid or missing | Check `VB_OPENAI_API_KEY` is set |
| STT not working | Deepgram API key invalid | Check `VB_DEEPGRAM_API_KEY` |
| High latency (>3s) | Using OpenAI Whisper for STT instead of Deepgram | Ensure `VB_DEFAULT_STT_PROVIDER=deepgram` |
| "RVC server unreachable" | RVC not running (expected) | Ignore — auto-disables, passes audio through |
| Import errors | Running from wrong directory | `cd voice-brain` first, then `python -m uvicorn main:app` |
| Browser mic denied | Not on HTTPS | Use `http://localhost:8601/test` (localhost is exempt from HTTPS requirement) |
