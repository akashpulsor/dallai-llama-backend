# Dalai Llama Platform — Integration & Testing Guide

## 1. Service Map

| Service | Port | Purpose |
|---------|------|---------|
| tenant-service | 8091 | Tenant lifecycle, provisioning, activation |
| product-service | 8082 | Products, plans, subscriptions, entitlements |
| billing-service | 8083 | Wallet, CDR rating, usage, billing state |
| pbx-core | 8080 | Telecom control plane, ESL, SIP auth, AI config |
| ai-service | 8601 (HTTP), 5555 (UDP) | Voice AI, STT/LLM/TTS pipeline, RTP transcription |
| Kamailio | 5060 (SIP) | SIP proxy, auth, routing |
| FreeSWITCH | 8021 (ESL) | Media server, call handling |
| RTPEngine | 2223 (ng) | Media relay, transcoding, RTP fork |
| Keycloak | 8081 | Identity, JWT, tenant realms |
| PostgreSQL | 5432 | Per-service databases |
| Redis | 6379 | Live call state, config cache |
| Kafka | 9092 | CDR events, billing events |
| MinIO | 9000 | Recordings, transcripts, exports |

---

## 2. Dashboard UI — Integration URLs

The **dashboard-ui** is the tenant's landing page showing all subscribed apps. URLs are generated during activation:

```
Pattern: https://{subdomain}-{tenant_slug}.dalaillama.in
```

### Per-Product App URLs (from product_apps seed)

**AI_CC (AI Contact Center):**
| App | Subdomain | URL Pattern | Roles |
|-----|-----------|-------------|-------|
| Agent Dashboard | `agent` | `https://agent-{slug}.dalaillama.in` | AGENT, SUPERVISOR, TENANT_ADMIN |
| Supervisor Dashboard | `supervisor` | `https://supervisor-{slug}.dalaillama.in` | SUPERVISOR, TENANT_ADMIN |
| Admin Panel | `admin` | `https://admin-{slug}.dalaillama.in` | TENANT_ADMIN |

**CONV_IVR:** agent + admin (same pattern)
**BASIC_PBX:** agent + admin
**OUTBOUND_DIALER:** agent + supervisor + admin
**VIRTUAL_RECEPTIONIST:** admin only

### Dashboard UI Config

The dashboard fetches app panels from tenant-service:
```
GET /api/v1/tenants/{tenantId}/apps → returns app_panels JSON with URLs
```

Each panel entry:
```json
{
  "type": "CONTACT_CENTER",
  "name": "Agent Dashboard",
  "url": "https://agent-acme.dalaillama.in",
  "icon": "📞",
  "order": 0
}
```

---

## 3. Frontend Integration Guide

### 3.1 Agent UI (`dalaillama/agent-ui`)

**Auth:** Keycloak OIDC → JWT token in `Authorization: Bearer {token}` header.
Keycloak realm = tenant slug, client = from `keycloak_client_suffix`.

**Backend endpoints (pbx-core :8080):**

| Feature | Method | Endpoint |
|---------|--------|----------|
| Agent login/status | POST | `/api/v1/agents/login` |
| Agent logout | POST | `/api/v1/agents/logout` |
| Set status (AVAILABLE/BREAK) | PUT | `/api/v1/agents/{agentId}/status` |
| Get active calls | GET | `/api/v1/calls/active` |
| Answer call | POST | `/api/v1/calls/{callId}/answer` |
| Hold/unhold | POST | `/api/v1/calls/{callId}/hold` or `/unhold` |
| Transfer (blind) | POST | `/api/v1/calls/{callId}/transfer` |
| Hangup | POST | `/api/v1/calls/{callId}/hangup` |
| Click-to-call | POST | `/api/v1/calls/originate` |
| Get SIP credentials | GET | `/api/v1/agents/{agentId}/sip-credentials` |
| Get TURN credentials | GET | `/api/v1/turn/credentials` |

**WebSocket (STOMP over SockJS):**
```
ws://pbx-core:8080/ws-stomp
Subscribe: /topic/tenant/{tenantId}/calls    → live call events
Subscribe: /topic/tenant/{tenantId}/agents   → agent status changes
Subscribe: /topic/tenant/{tenantId}/queues   → queue metrics
```

**WebRTC SIP:**
- Use JsSIP or SIP.js
- SIP server: `wss://sip-{slug}.dalaillama.in` (or Kamailio WSS)
- TURN: fetch from `/api/v1/turn/credentials`
- Register with SIP credentials from agent login

### 3.2 Supervisor UI (`dalaillama/supervisor-ui`)

Same auth + WebSocket as agent-ui, plus:

| Feature | Method | Endpoint |
|---------|--------|----------|
| List agents | GET | `/api/v1/supervisors/agents` |
| Monitor call (listen) | POST | `/api/v1/supervisors/calls/{callId}/listen` |
| Whisper to agent | POST | `/api/v1/supervisors/calls/{callId}/whisper` |
| Barge into call | POST | `/api/v1/supervisors/calls/{callId}/barge` |
| Queue stats | GET | `/api/v1/queues/{queueId}/stats` |
| Campaign stats | GET | `/api/v1/campaigns/{campaignId}/stats` |

### 3.3 Admin UI (`dalaillama/admin-ui`)

| Feature | Method | Endpoint |
|---------|--------|----------|
| Create agent | POST | `/api/v1/agents` |
| List agents | GET | `/api/v1/agents` |
| Create queue | POST | `/api/v1/queues` |
| Create routing policy | POST | `/api/v1/routing/policies` |
| Manage bots | POST/GET | `/api/v1/bots` |
| Manage campaigns | POST/GET | `/api/v1/campaigns` |
| Upload contacts | POST | `/api/v1/campaigns/{id}/contacts/upload` |
| SIP trunk config | GET | `/api/v1/trunks` |
| CDR/call records | GET | `/api/v1/calls/records` |
| Provisioning status | GET | tenant-service `/api/v1/tenants/apps/{id}/provision/status` |
| Provisioning logs | GET | tenant-service `/api/v1/tenants/apps/{id}/provision/logs` |

---

## 4. End-to-End Testing Guide

### 4.1 Prerequisites

1. All services running (docker-compose or K8s)
2. PostgreSQL databases created: `tenant_db`, `product_db`, `billing_db`, `pbx_core`
3. Flyway migrations applied (auto on startup)
4. Redis, Kafka, MinIO running
5. Keycloak running with master realm
6. FreeSWITCH + Kamailio + RTPEngine running

### 4.2 Seed Data Setup

```bash
# 1. Product service seeds products + plans + entitlements via Flyway
#    Verify:
curl http://localhost:8082/api/v1/products
# Should return: AI_CC, CONV_IVR, BASIC_PBX, OUTBOUND_DIALER, VIRTUAL_RECEPTIONIST

curl http://localhost:8082/api/v1/plans
# Should return plans per product (e.g., AI_CC_STARTER, AI_CC_PROFESSIONAL)

# 2. Verify billing rate cards exist
curl http://localhost:8083/api/v1/rate-plans
```

### 4.3 Tenant Onboarding Flow

```bash
# STEP 1: Create tenant
curl -X POST http://localhost:8091/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Acme Corp",
    "slug": "acme",
    "email": "admin@acme.com",
    "phone": "+919876543210"
  }'
# Save: tenantId

# STEP 2: Subscribe to product (creates payment order)
curl -X POST http://localhost:8082/api/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "{tenantId}",
    "productCode": "AI_CC",
    "planCode": "AI_CC_STARTER",
    "agentCount": 5,
    "did": {
      "number": "+911234567890",
      "country": "IN",
      "region": "Maharashtra",
      "city": "Mumbai"
    }
  }'
# Save: subscriptionId, gatewayOrderId
# Status will be PENDING_PAYMENT

# STEP 3: Simulate payment success → activate subscription
curl -X POST http://localhost:8082/api/v1/internal/products/subscriptions/{subscriptionId}/activate
# This provisions: DID, SIP endpoint, channels, tenant SIP trunk, plan assignment
# Then notifies tenant-service → creates TenantApp with app_panels
# Save: tenantAppId from response

# STEP 4: Provision infrastructure
curl -X POST http://localhost:8091/api/v1/tenants/apps/{tenantAppId}/provision
# Provisions: Keycloak realm, Kamailio config, FreeSWITCH config, RTPEngine, TURN, Istio routes

# STEP 5: Check provisioning status
curl http://localhost:8091/api/v1/tenants/apps/{tenantAppId}/provision/status
```

### 4.4 Create Agent & Test Login

```bash
# Create agent via pbx-core (admin role required)
curl -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {admin_jwt}" \
  -d '{
    "tenantId": "{tenantId}",
    "name": "Agent One",
    "email": "agent1@acme.com",
    "extension": "1001",
    "role": "AGENT"
  }'
# This creates: agent record + Keycloak user + SIP subscriber

# Agent login
curl -X POST http://localhost:8080/api/v1/agents/login \
  -H "Authorization: Bearer {agent_jwt}"
# Returns: SIP credentials, TURN credentials, agent profile
# Agent status → AVAILABLE
```

### 4.5 Test Actual Call Flow

#### Inbound Call Test
```
1. Caller dials DID (+911234567890)
2. PSTN → DIDWW → Kamailio (port 5060)
3. Kamailio → POST /internal/kamailio/authorize/inbound (pbx-core)
   - Resolves tenant by DID
   - Checks subscription active, channel limit
   - Returns routing policy (queue, ring group, or IVR)
4. Kamailio → FreeSWITCH (via dispatcher)
5. FreeSWITCH → applies dialplan from pbx-core
   - If bot enabled: mod_audio_stream → ws://ai-service:8601/audio/{callId}
   - If queue: park → queue → ring agent
6. Agent answers → CHANNEL_BRIDGE event
   - If AI fork enabled: registers SSRC with ai-service
   - RTPEngine forks media to ai-service:5555/udp
7. Call ends → CHANNEL_HANGUP
   - CDR finalized → Kafka → billing-service rates + debits wallet
   - AI streams closed, final transcript sent
```

#### Test with Softphone
```bash
# 1. Get SIP credentials for the agent
curl http://localhost:8080/api/v1/agents/{agentId}/sip-credentials \
  -H "Authorization: Bearer {agent_jwt}"

# 2. Configure softphone (Opal, Opal, MicroSIP, or Opal):
#    Server: sip.dalaillama.in (or Kamailio IP)
#    Username: from sip-credentials response
#    Password: from sip-credentials response
#    Transport: UDP or WSS

# 3. Register softphone → Kamailio authenticates via pbx-core

# 4. Call the DID from a mobile/PSTN phone
# 5. Observe in pbx-core logs:
#    - CHANNEL_CREATE → CHANNEL_ANSWER → CHANNEL_BRIDGE → CHANNEL_HANGUP
# 6. Check active calls:
curl http://localhost:8080/api/v1/calls/active \
  -H "Authorization: Bearer {agent_jwt}"
```

### 4.6 Verify Each Product Works

| Product | Key Test |
|---------|----------|
| **AI_CC** | Inbound call → bot answers → STT → LLM → TTS → escalation to agent → agent answers |
| **CONV_IVR** | Inbound call → conversational IVR bot → collect info → escalate or hang up |
| **BASIC_PBX** | Inbound call → queue → agent ring → answer → hold → transfer |
| **OUTBOUND_DIALER** | Create campaign + contacts → start campaign → dialer originates → agent bridged |
| **VIRTUAL_RECEPTIONIST** | Inbound call → bot greets → handles query → no human agent needed |

### 4.7 Verify Cross-Service Integration

```bash
# 1. CDR + Billing
#    After a call ends, check:
curl http://localhost:8083/api/v1/wallets/{tenantId}/balance
curl http://localhost:8083/api/v1/usage?tenantId={tenantId}

# 2. Recordings (MinIO)
#    pbx-core uploads to: dl-{tenantId}/recordings/{callId}.wav
curl http://localhost:8080/api/v1/calls/{callId}/recording

# 3. AI transcripts
#    After bot/agent call, check ai-service analytics:
curl http://localhost:8601/analytics/{callId}/summary

# 4. Bot configuration
curl http://localhost:8080/internal/ai/config?tenantId={tenantId}&callId=test

# 5. Health checks
curl http://localhost:8091/actuator/health  # tenant-service
curl http://localhost:8082/actuator/health  # product-service
curl http://localhost:8083/actuator/health  # billing-service
curl http://localhost:8080/actuator/health  # pbx-core
curl http://localhost:8601/health           # ai-service
```

### 4.8 Low Balance / Billing Block Test

```bash
# 1. Drain wallet to near zero
# 2. Make a call → CDR rated → wallet debited
# 3. billing-service evaluates state:
#    balance <= 0 → GRACE (7 day grace period)
#    grace expired → BLOCKED
#    BLOCKED → Kafka event → tenant-service suspends tenant
# 4. Top up wallet → balance > 0 → state → ACTIVE again
# 5. Verify pbx-core rejects calls when tenant is SUSPENDED:
#    Kamailio authorize returns 403
```

---

## 5. Product Feature Matrix (Verification Checklist)

| Feature | AI_CC | CONV_IVR | BASIC_PBX | OUTBOUND_DIALER | VIRTUAL_RECEPTIONIST |
|---------|-------|----------|-----------|-----------------|---------------------|
| Inbound calls | ✅ | ✅ | ✅ | ❌ | ✅ |
| Outbound calls | ✅ | ❌ | ✅ | ✅ | ❌ |
| AI Bot (STT+LLM+TTS) | ✅ | ✅ | ❌ | ✅ | ✅ |
| Agent queues | ✅ | ✅ | ✅ | ✅ | ❌ |
| AI sentiment | ✅ | ✅ | ❌ | ❌ | ❌ |
| AI transcription | ✅ | ✅ | ❌ | ❌ | ❌ |
| RTP fork (agent assist) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Campaign dialer | ❌ | ❌ | ❌ | ✅ | ❌ |
| Recording | ✅ | ✅ | ✅ | ✅ | ❌ |
| Supervisor barge/whisper | ✅ | ❌ | ❌ | ✅ | ❌ |
| CRM integration | ✅ | ❌ | ❌ | ✅ | ❌ |

---

## 6. Environment Variables Quick Reference

### tenant-service
```
DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD
REDIS_HOST, REDIS_PORT, KAFKA_BOOTSTRAP_SERVERS
KEYCLOAK_ISSUER_URI, KEYCLOAK_ADMIN_URL, KEYCLOAK_CLIENT_ID, KEYCLOAK_CLIENT_SECRET
BASE_DOMAIN=dalaillama.in
MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY
PBX_CORE_URL, PRODUCT_SERVICE_URL, BILLING_SERVICE_URL
```

### product-service
```
DB_HOST, DB_PORT, DB_NAME, REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS
BILLING_SERVICE_URL, TENANT_SERVICE_URL
sip.domain=sip.dalaillama.in
```

### billing-service
```
DB_HOST, DB_PORT, DB_NAME, REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS
```

### pbx-core
```
DB_HOST, DB_PORT, DB_NAME, REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS
FREESWITCH_ESL_HOST, FREESWITCH_ESL_PORT, FREESWITCH_ESL_PASSWORD
MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY
AI_SERVICE_URL (for RTP session registration)
```

### ai-service
```
PBX_CORE_URL, VOICEBRAIN_RTP_PORT=5555
DEEPGRAM_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY
DATABASE_URL (analytics PostgreSQL)
```
