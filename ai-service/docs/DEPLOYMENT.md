# Voice-Brain Deployment

This service is designed to run behind PBX-Core and receive audio over WebSocket.

## Required Environment Variables

All service settings use the `VB_` prefix.

### Core service

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_HOST` | No | Bind host. Default `0.0.0.0`. |
| `VB_PORT` | No | Bind port. Default `8601`. |
| `VB_LOG_LEVEL` | No | Logging level. Default `INFO`. |
| `VB_SERVICE_PUBLIC_URL` | Yes | Reachable base URL for this service in Kubernetes or ingress. |
| `VB_PBX_CORE_URL` | Yes | Base URL of PBX-Core for AI config fetch and callback APIs. |
| `VB_DATABASE_URL` | Yes for persistent analytics | Async SQLAlchemy Postgres URL. |
| `VB_DB_ECHO` | No | Enable SQL logging. |
| `VB_DB_POOL_SIZE` | No | Async DB pool size. |
| `VB_DB_MAX_OVERFLOW` | No | Async DB pool overflow. |

### Provider selection

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_DEFAULT_STT_PROVIDER` | No | Default STT provider when PBX-Core does not override. |
| `VB_DEFAULT_TTS_PROVIDER` | No | Default TTS provider when PBX-Core does not override. |
| `VB_DEFAULT_LLM_PROVIDER` | No | Default LLM provider when PBX-Core does not override. |

### OpenAI

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_OPENAI_API_KEY` | If using OpenAI | Shared API key for OpenAI LLM/TTS/STT. |
| `VB_OPENAI_LLM_MODEL` | No | Default OpenAI LLM model. |
| `VB_OPENAI_TTS_MODEL` | No | Default OpenAI TTS model. |
| `VB_OPENAI_TTS_VOICE` | No | Default OpenAI TTS voice. |
| `VB_OPENAI_STT_MODEL` | No | Default OpenAI STT model. |

### Deepgram

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_DEEPGRAM_API_KEY` | If using Deepgram | Shared key for Deepgram STT/TTS. |
| `VB_DEEPGRAM_STT_MODEL` | No | Default Deepgram STT model. |
| `VB_DEEPGRAM_TTS_MODEL` | No | Default Deepgram TTS voice/model. |

### Ollama

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_OLLAMA_BASE_URL` | If using Ollama | Base URL of the Ollama OpenAI-compatible endpoint. |
| `VB_OLLAMA_LLM_MODEL` | If using Ollama | Default local model name, for example `gemma2:2b`. |

### Optional local Indic TTS

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_INDIC_TTS_BASE_URL` | Only if using `indic_tts` | Base URL for local TTS service. |
| `VB_INDIC_TTS_SAMPLE_RATE` | No | Output sample rate. |
| `VB_INDIC_TTS_VOICE` | No | Voice preset. |
| `VB_INDIC_TTS_EMOTION` | No | Emotion preset. |

### Optional RVC

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_RVC_ENABLED` | No | Enable RVC post-processing. |
| `VB_RVC_SERVER_URL` | If `VB_RVC_ENABLED=true` | RVC service URL. |
| `VB_RVC_TIMEOUT_MS` | No | RVC chunk timeout. |

### Audio processing

| Variable | Required | Purpose |
| --- | --- | --- |
| `VB_SAMPLE_RATE` | No | Default audio sample rate. |
| `VB_AUDIO_NORMALIZE` | No | Output normalization toggle. |
| `VB_AUDIO_TARGET_LUFS` | No | Output loudness target. |
| `VB_AUDIO_HIGHPASS_HZ` | No | Input cleanup high-pass filter. |
| `VB_AUDIO_LIMITER_THRESHOLD` | No | Output limiter threshold. |

## Production Recommendation

For telephony production, use PBX-Core to drive per-bot provider settings and keep this service defaults conservative.

Recommended baseline:

- STT: `deepgram`
- TTS: provider with Indian voices, not Deepgram Aura English voices
- LLM: `openai` or a low-latency hosted model

Current code supports `openai`, `deepgram`, `ollama`, and local `indic_tts`. If you move to Google or another Indian-voice provider, add that provider in the TTS selection layer before deployment.

## Docker

Build:

```bash
docker build -t voice-brain:latest .
```

Run:

```bash
docker run --rm -p 8601:8601 --env-file .env voice-brain:latest
```

## Kubernetes Notes

- Set `VB_PBX_CORE_URL` to the PBX-Core service DNS name.
- Set `VB_SERVICE_PUBLIC_URL` to the in-cluster service URL or ingress URL used by PBX-Core.
- Set `VB_DATABASE_URL` to the Postgres service DSN.
- Put `VB_OPENAI_API_KEY` and `VB_DEEPGRAM_API_KEY` in a `Secret`, not a `ConfigMap`.
- If using Ollama in-cluster, point `VB_OLLAMA_BASE_URL` at the Ollama service.

Example service URL values:

- Cluster-internal: `http://voice-brain.default.svc.cluster.local:8601`
- Ingress: `https://voice-brain.example.com`

Service probe endpoints:

- Liveness: `GET /health`
- Readiness: `GET /ready`

`/ready` validates:

- DB connectivity when `VB_DATABASE_URL` is set
- required provider keys for the configured default STT/TTS/LLM providers

## Intent Dashboard

This service can now persist analytics events to Postgres when `VB_DATABASE_URL` is set.

Run the migration manually before starting the service:

```sql
\i migrations/001_create_analytics_events.sql
```

Persisted event columns include:

- `call_id`
- `tenant_id`
- `product_code`
- `timestamp`
- `speaker`
- `utterance`
- `intent`
- `intent_confidence`
- `tone_label`
- `tone_score`
- `event_type`

Dashboard-ready endpoints:

- `GET /analytics/tenants/{tenant_id}/overview?from_ts=...&to_ts=...`
- `GET /analytics/tenants/{tenant_id}/customer?from_ts=...&to_ts=...`
- `GET /analytics/tenants/{tenant_id}/bot?from_ts=...&to_ts=...`
- `GET /analytics/tenants/{tenant_id}/agent?from_ts=...&to_ts=...`
- `POST /analytics/events`

Notes:

- `customer` analytics are persisted from final transcripts and sentiment analysis.
- `bot` analytics are persisted from generated bot replies, intents, and tone hints.
- `agent` analytics are schema-ready, but this service does not generate agent turns by itself yet. Use `POST /analytics/events` from PBX-Core or another service to store agent-side events.
