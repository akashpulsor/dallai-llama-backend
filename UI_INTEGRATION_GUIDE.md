# Dalai Llama — UI ↔ Backend v5 Integration Guide

> **Purpose**: Rewire the existing v4 UI (`C:\Users\Akash\workspace\v4\dalai-llama`) to the v5 backend (`dallai-llama-backend`).
> The UI already works with mock data. This guide maps every UI feature to the real backend endpoint and highlights what needs changing.

---

## 1. Architecture Overview

### Backend Services (v5)

| Service | Port | Base Path | Purpose |
|---------|------|-----------|---------|
| **tenant-service** | 8091 | `/api/v1/tenants` | Tenant CRUD, provisioning, compliance |
| **product-service** | 8082 | `/api/v1/products`, `/api/v1/plans`, `/api/v1/subscriptions`, `/api/v1/did` | Catalog, plans, subscriptions, DIDs, SIP trunks |
| **billing-service** | 8083 | `/api/v1/tenants/{tid}/...` | Wallet, payments, CDRs, usage, billing state |
| **pbx-core** | 8080 | `/api/v1/agents`, `/api/v1/calls`, etc. | Agents, calls, queues, bots, campaigns, routing, trunks, supervisor |
| **agent-service** | 8084 | `/api/v1/tenants/{tid}/agents` | Agent routing, media (barge/whisper/monitor), presence |
| **ai-service** | 8601 | `/api/`, `/health` | Voice AI pipeline, RTP transcription |
| **Keycloak** | 8081 | — | Identity, JWT, per-tenant realms |

### UI Apps (v4)

| App | Port | Router Base | Role |
|-----|------|------------|------|
| **platform-ui** | 5173 | `/platform` | Landing page, subscription purchase, OAuth callback |
| **dashboard-ui** | 5177 | `/` | Tenant admin dashboard (onboarding wizard, DID, trunks, billing) |
| **admin-ui** | 5174 | `/` | Tenant operations (agents, queues, bots, campaigns, routing, settings) |
| **agents-ui** | — | `/` | Agent softphone console, call history, profile |
| **supervisor-ui** | — | — | Supervisor cockpit (scaffolded, not built) |

---

## 2. API Gateway / Proxy Strategy

### Problem
The UI uses a **single** `API_BASE_URL` (via `apiSlice.js`) but v5 has **6 microservices** on different ports.

### Solution: Vite Dev Proxy (local dev)

Each Vite app should proxy routes to the correct backend service:

```js
// vite.config.js — recommended proxy for admin-ui / agents-ui
server: {
  port: 5174,
  proxy: {
    // pbx-core (agents, calls, queues, bots, campaigns, routing, trunks, supervisor, turn)
    '/api/v1/agents':       { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/calls':        { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/queues':       { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/bots':         { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/campaigns':    { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/routing':      { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/trunks':       { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/turn':         { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/supervisor':   { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/dnc':          { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/crm':          { target: 'http://localhost:8080', changeOrigin: true },
    '/api/v1/provisioning': { target: 'http://localhost:8080', changeOrigin: true },

    // tenant-service
    '/api/v1/tenants':      { target: 'http://localhost:8091', changeOrigin: true },
    '/api/v1/public':       { target: 'http://localhost:8091', changeOrigin: true },

    // product-service
    '/api/v1/products':     { target: 'http://localhost:8082', changeOrigin: true },
    '/api/v1/plans':        { target: 'http://localhost:8082', changeOrigin: true },
    '/api/v1/subscriptions':{ target: 'http://localhost:8082', changeOrigin: true },
    '/api/v1/did':          { target: 'http://localhost:8082', changeOrigin: true },

    // billing-service (all endpoints under /api/v1/billing/)
    '/api/v1/billing':                 { target: 'http://localhost:8083', changeOrigin: true },
    '/api/v1/webhooks/razorpay':       { target: 'http://localhost:8083', changeOrigin: true },

    // WebSocket (STOMP)
    '/ws':                  { target: 'http://localhost:8080', ws: true },
  },
}
```

### Production: Istio Gateway + VirtualService

All traffic enters via Istio Gateway on `*.dalaillama.in`. Backend VirtualServices
route by path prefix on `api.dalaillama.in`. Tenant UI VirtualServices route by
`Host` header to the correct frontend pod.

---

## 2b. DNS & Istio Routing (Production)

### DNS Record (single wildcard)

| Type | Name | Value | Proxy |
|------|------|-------|-------|
| **A** | `*.dalaillama.in` | `<server-ip>` | ☁️ Cloudflare (optional) |

This one record covers **all** tenant subdomains automatically.
No per-tenant DNS changes needed.

### Subdomain Pattern (hyphen-separated)

| URL | Istio VirtualService | K8s Service |
|-----|---------------------|-------------|
| `admin-acme.dalaillama.in` | `tenant-routes-admin-ui` | `admin-ui:80` |
| `agent-acme.dalaillama.in` | `tenant-routes-agent-ui` | `agent-ui:80` |
| `supervisor-acme.dalaillama.in` | `tenant-routes-supervisor-ui` | `supervisor-ui:80` |
| `app-acme.dalaillama.in` | `tenant-routes-dashboard-ui` | `dashboard-ui:80` |
| `api.dalaillama.in` | per-service VS | backend pods |
| `auth.dalaillama.in` | keycloak VS | keycloak:80 |
| `platform.dalaillama.in` | platform-ui VS | platform-ui:80 |

### TLS

- **Wildcard cert**: `*.dalaillama.in` via cert-manager (DNS-01 challenge)
- Secret: `wildcard-dalaillama-in-tls` in `istio-system`
- Covers all tenant subdomains automatically

### How the `IstioRouteReconciler` works

```
tenant-service (every 5 min + on provisioning):
  1. Query DB for all COMPLETED + RUNNING TenantApps
  2. For each app, extract hosts:
     admin-acme.dalaillama.in, agent-acme.dalaillama.in, ...
  3. Group by target service:
     admin-ui → [admin-acme, admin-beta, ...]
     agent-ui → [agent-acme, agent-beta, ...]
  4. Create/update one VirtualService per service group
  5. Delete orphaned VirtualServices for deprovisioned tenants
```

### Security (Istio-level)

- **Rate limiting**: 30 req/min on `/api/v1/public/*` (EnvoyFilter local rate limit)
- **AuthorizationPolicy**: DENY unauthenticated requests on all paths except `/api/v1/public/*` and `/actuator/health`
- **Slug validation**: regex `^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$` at controller level

---

## 3. Auth Flow — What to Rewire

### Current State
- `platform-ui` uses `keycloakApi.js` (RTK mutation-based token exchange) — **works for platform-level Keycloak**.
- `admin-ui`, `agents-ui` use `useTenantAuth.js` — **correct pattern**, needs one backend endpoint.

### Backend Endpoint — ✅ IMPLEMENTED
`useTenantAuth.js` fetches:
```
GET /api/v1/public/tenant-config/{slug}
```
This endpoint is implemented in **tenant-service** → `PublicTenantConfigController.java`.

It returns (public fields only — sensitive data like capacity, SIP IPs, plan_tier moved to authenticated endpoint):
- `name`, `slug`, `company_name`, `timezone`, `country`, `status`
- `keycloak_url` — Keycloak auth server (e.g. `https://auth.dalaillama.in`)
- `keycloak_realm` — realm name (e.g. `tenant-{uuid}`)
- `keycloak_issuer` — OIDC issuer URL (e.g. `https://auth.dalaillama.in/realms/tenant-{uuid}`)
- `domain` — tenant domain (e.g. `acme.dalaillama.in`)
- `apps[]` — per-app: `app_type`, `product_code`, `display_name`, `keycloak_client_id`, `dashboard_url`, `websocket_url`, `turn_url`
- `apps[].features` — feature flags (recording, ai_transcription, ai_sentiment, ai_bot, barge, whisper, etc.)

This populates `tenantSlice` which drives `useSipPhone`, `useStompEvents`, and feature gating.

### Full Auth Flow (Istio → Public Config → Keycloak → App)

```
1. User visits: https://admin-acme.dalaillama.in
                 ↓
2. DNS: *.dalaillama.in → server IP (wildcard A record)
   TLS: wildcard cert *.dalaillama.in (cert-manager DNS-01)
                 ↓
3. Istio Gateway accepts → VirtualService "tenant-routes-admin-ui"
   matches Host header → routes to admin-ui pod
                 ↓
4. admin-ui loads, useTenantAuth parses hostname:
   "admin-acme.dalaillama.in" → { appType: "admin", slug: "acme" }
                 ↓
5. UI calls: GET https://api.dalaillama.in/api/v1/public/tenant-config/acme
   (no JWT required — public endpoint, rate-limited 30 req/min)
                 ↓
6. tenant-service returns:
   {
     keycloak_url: "https://auth.dalaillama.in",
     keycloak_realm: "tenant-abc123",
     keycloak_issuer: "https://auth.dalaillama.in/realms/tenant-abc123",
     status: "ACTIVE",
     apps: [{
       keycloak_client_id: "dalaillama-acme",
       dashboard_url: "https://admin-acme.dalaillama.in",
       websocket_url: "wss://ws-acme.dalaillama.in/ws",
       turn_url: "turn:turn.dalaillama.in:3478",
       features: { recording: true, ai_bot: true, ... }
     }]
   }
                 ↓
7. UI initializes Keycloak JS adapter:
   - url: keycloak_url
   - realm: keycloak_realm
   - clientId: apps[0].keycloak_client_id
   - onLoad: 'login-required', pkceMethod: 'S256'
                 ↓
8. Keycloak login page (tenant-specific realm)
                 ↓
9. JWT returned with claims: { tenant_id, app_type, roles[] }
                 ↓
10. UI stores JWT, populates tenantSlice, renders app
```

**If `status` is `PROVISIONING`**: UI should show provisioning progress page instead of login. Subscribe to WebSocket `/topic/tenant/{tenantId}/provisioning` for real-time updates.

**If `status` is `PROVISIONING_FAILED`**: UI should show error + "Retry" button → `POST /api/v1/tenants/apps/{tenantAppId}/provision`.

### Auth Summary

| App | Auth Method | Status |
|-----|------------|--------|
| platform-ui | `keycloakApi.js` → platform realm | ✅ Works (needs Keycloak running) |
| admin-ui | `useTenantAuth('admin')` → tenant realm | ⚠️ Hardcoded mock — uncomment real auth, endpoint is ready |
| agents-ui | `useTenantAuth('agent')` → tenant realm | ⚠️ Same — uncomment, endpoint is ready |
| supervisor-ui | `useTenantAuth('supervisor')` | ⚠️ Same — uncomment, endpoint is ready |

---

## 4. API Slice Rewiring — `apiSlice.js`

The generic `apiSlice.js` in `shared/store/slices/` has ~120 endpoints, many with **stale/incorrect URLs**. Below is the complete mapping to real v5 endpoints.

### 4.1 Tenant Onboarding (platform-ui / dashboard-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useTenantRegisterMutation` | `POST /tenants` | `POST /api/v1/tenants` | tenant-service:8091 |
| `useGetTenantsQuery` | `GET /tenants` | `GET /api/v1/tenants` | tenant-service:8091 |
| `useGetTenantsListQuery` | `GET /tenants/list` | `GET /api/v1/tenants` | tenant-service:8091 |
| `useGetTenantAnalyticsQuery` | `GET /tenants/analytics?tenantId=` | ✅ `GET /api/v1/billing/{tid}/analytics?days=30` | billing-service:8083 |
| `useUpdateTenantMutation` | `PUT /tenants/update` | `PUT /api/v1/tenants/{id}` | tenant-service:8091 |
| `useDeleteTenantMutation` | `DELETE /tenants/delete/{id}` | `DELETE /api/v1/tenants/{id}?reason=...` | tenant-service:8091 |

### 4.2 Products & Plans (platform-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useGetProductsQuery` | `GET /products` | `GET /api/v1/products` | product-service:8082 |
| `useGetPlansQuery` | `GET /plans/{userId}` | `GET /api/v1/plans` or `GET /api/v1/plans/product/{productCode}` | product-service:8082 |
| `useAddPlansMutation` | `POST /plans` | ❌ Plans are seeded, not user-created |

### 4.3 Subscriptions (platform-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useCreateSubscriptionMutation` | `POST /subscriptions` | `POST /api/v1/subscriptions` | product-service:8082 |
| `useSimulatePaymentSuccessMutation` | `POST /internal/payments/simulate-success/{id}` | `POST /api/v1/internal/products/subscriptions/{id}/activate` | product-service:8082 |
| `useLazyGetSubscriptionStatusQuery` | `GET /subscriptions/{id}` | `GET /api/v1/subscriptions/{id}` | product-service:8082 |
| `useGetSubscriptionQuery` | `GET /subscription/{userId}` | `GET /api/v1/subscriptions?tenantId={tenantId}` | product-service:8082 |
| `usePurchaseAddonMutation` | `POST /subscription/add-on` | ❌ **Not yet implemented** |

### 4.4 DIDs (dashboard-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useSearchAvailableDidsQuery` | `GET /did/available` | `GET /api/v1/did/available?country=&city=&type=&limit=` | product-service:8082 |
| `useGetDidInventoryQuery` | `GET /did/inventory` | `GET /api/v1/tenants/{tenantId}/dids` | product-service:8082 |
| `useBuyDidMutation` | `POST /did/buy` | `POST /api/v1/tenants/{tenantId}/dids` (provision DID) | product-service:8082 |
| `useSaveDidMutation` | `POST /did/save` | ❌ Use `POST /api/v1/tenants/{tenantId}/dids` instead |

### 4.5 Billing & Wallet (dashboard-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useGetWalletBalanceQuery` | `GET /wallet/balance` | `GET /api/v1/billing/{tid}/wallet` | billing-service:8083 |
| `useGetBillingSummaryQuery` | `GET /billing/{userId}` | `GET /api/v1/billing/{tid}/billing-state` | billing-service:8083 |
| `useGetPaymentMethodsQuery` | `GET /payment/{userId}` | `GET /api/v1/billing/{tid}/payments` | billing-service:8083 |
| `useAddPaymentMethodMutation` | `POST /payment/method` | `POST /api/v1/billing/{tid}/payments` | billing-service:8083 |
| `useGetInvoicesQuery` | `GET /billing/invoices` | ✅ `GET /api/v1/billing/{tid}/monthly?months=12` | billing-service:8083 |
| `useGetMonthlyBillingQuery` | `GET /billing/monthly` | ✅ `GET /api/v1/billing/{tid}/monthly?months=6` | billing-service:8083 |
| `useGetLicensesQuery` | `GET /billing/licenses` | `GET /api/v1/subscriptions?tenantId={tid}` | product-service:8082 |
| `useDownloadInvoiceMutation` | `POST /download/invoice` | ✅ `GET /api/v1/billing/{tid}/invoice/{YYYY-MM}` (returns text file) | billing-service:8083 |
| `useUpdateAutoDebitMutation` | `POST /update/autodebit` | ❌ Not yet |

### 4.6 Compliance (dashboard-ui)

| UI Hook | Current URL | Real v5 Endpoint | Service |
|---------|------------|-------------------|---------|
| `useGetCompliancePoliciesQuery` | `GET /compliance/policy/{id}` | `GET /api/v1/tenants/{tid}/compliance` | tenant-service:8091 |
| `useUpdateCompliancePoliciesMutation` | `POST /compliance/policies/save` | `PUT /api/v1/tenants/{tid}/compliance` | tenant-service:8091 |
| `useGetRecordingPoliciesQuery` | `GET /recording/policy/{id}` | `GET /api/v1/tenants/{tid}/compliance/recording` | tenant-service:8091 |
| `useUpdateGlobalPoliciesMutation` | `POST /global/policies/save` | `PUT /api/v1/tenants/{tid}/compliance/recording` | tenant-service:8091 |

---

## 5. `pbxCoreApi.js` — Already Correct ✅

The `shared/store/slices/pbxCoreApi.js` file **already maps correctly** to the v5 `pbx-core` controllers. It uses a separate RTK Query API instance with `baseUrl: '/api/v1'`.

### What's covered:
- **Agents**: CRUD, status, SIP credentials, me → `AgentController`
- **Calls**: originate, answer, hold, transfer, hangup, DTMF → `CallController`
- **Supervisor**: listen, whisper, barge, dashboard → `SupervisorController`
- **Bots**: CRUD, escalation intents, knowledge docs → `BotController`
- **Campaigns**: CRUD, start/pause/resume/cancel → `CampaignController`
- **Contacts**: import, list, stats → `ContactController`
- **DNC**: add, remove, list → `ContactController`
- **Queues**: CRUD, members, stats → `QueueController`
- **Routing**: policies, IVR flows → `RoutingController`
- **Trunks**: CRUD → `TrunkController`
- **TURN**: credentials → `TurnController`

### Action Required
admin-ui and agents-ui pages should **switch from `apiSlice.js` hooks to `pbxCoreApi.js` hooks** for all PBX operations. Example:

```diff
- import { useAddAgentMutation, useGetQueuesQuery } from '@dalaillama/shared-store/slices/apiSlice';
+ import { useCreateAgentMutation, useListQueuesQuery } from '@dalaillama/shared-store/slices/pbxCoreApi';
```

---

## 6. WebSocket (STOMP) — Ready ✅

`shared/hooks/useStompEvents.js` is correctly implemented. It reads `stompWsUrl` and `tenantId` from `tenantSlice`.

### Requirements
1. `tenantSlice.stompWsUrl` must be populated (from `/api/v1/public/tenant-config/{slug}`)
2. Vite proxy must forward `/ws` → `http://localhost:8080` with `ws: true`
3. pbx-core WebSocket endpoint: `/ws-stomp` (SockJS)

### Available Topics
```
/topic/tenant/{tenantId}/calls       → call events (create/answer/hangup/bridge)
/topic/tenant/{tenantId}/agents      → agent status changes
/topic/tenant/{tenantId}/queues      → queue metric updates
/topic/tenant/{tenantId}/campaigns   → campaign progress updates
```

---

## 7. SIP Softphone — Ready ✅

`shared/hooks/useSipPhone.js` uses SIP.js, reads from `tenantSlice`:
- `sipWssUrl` → Kamailio WSS endpoint
- `turnUrl` → TURN credentials from pbx-core
- Fetches SIP creds from `GET /api/v1/agents/me/sip-credentials`

### Requirements
1. Kamailio running with WSS transport
2. pbx-core `AgentController.getSipCredentials()` returns `{ extension, sip_domain, sip_password, sip_wss_url }`
3. TURN server (coTURN) running
4. `tenantSlice` populated via tenant-config endpoint

---

## 8. Page-by-Page Rewiring Guide

### 8.1 admin-ui Pages

| Page | File | What It Needs | API Hooks to Use |
|------|------|---------------|------------------|
| **Dashboard** | `pages/Dashboard.jsx` | Tenant stats, agent count, call count | `pbxCoreApi.useGetSupervisorDashboardQuery` + billing `GET /billing-state` |
| **Agents** | `pages/Agents.jsx` | List, create, update agents | `pbxCoreApi.useListAgentsQuery`, `useCreateAgentMutation`, `useUpdateAgentMutation` |
| **Queues** | `pages/Queues.jsx` | CRUD queues, manage members | `pbxCoreApi.useListQueuesQuery`, `useCreateQueueMutation`, `useAddQueueMemberMutation` |
| **Bots** | `pages/Bots.jsx` | CRUD bots, knowledge, escalation | `pbxCoreApi.useListBotsQuery`, `useCreateBotMutation`, `useUpdateBotMutation` |
| **BotTest** | `pages/BotTest.jsx` | Test bot conversation | WebSocket to ai-service (needs new endpoint) |
| **Campaigns** | `pages/Campaigns.jsx` | CRUD campaigns, import contacts | `pbxCoreApi.useListCampaignsQuery`, `useCreateCampaignMutation`, `useImportContactsMutation` |
| **Calls** | `pages/Calls.jsx` | Live calls, CDR history | `pbxCoreApi.useGetActiveCallsQuery` + billing `GET /cdrs` |
| **Routing** | `pages/Routing.jsx` | Routing policies, IVR flows | `pbxCoreApi.useListRoutingPoliciesQuery`, `useCreateRoutingPolicyMutation`, `useListIvrFlowsQuery` |
| **Billing** | `pages/Billing.jsx` | Wallet, payments, CDRs | billing-service endpoints via apiSlice (rewired) |
| **Settings** | `pages/Settings.jsx` | Compliance, recording policies, branding | tenant-service compliance endpoints |

### 8.2 agents-ui Pages

| Page | File | What It Needs | API Hooks to Use |
|------|------|---------------|------------------|
| **Console** | `pages/Console.jsx` | SIP phone, live call, transcript | `useSipPhone`, `useStompEvents`, `pbxCoreApi.useGetActiveCallsQuery` |
| **History** | `pages/History.jsx` | Past calls, recordings | billing-service `GET /cdrs` |
| **Profile** | `pages/Profile.jsx` | Agent profile | `pbxCoreApi.useGetMeQuery` |

### 8.3 platform-ui Pages

| Page | File | What It Needs | API Hooks to Use |
|------|------|---------------|------------------|
| **LandingPage** | `pages/LandingPage.jsx` | Product catalog, plan selection | `apiSlice.useGetProductsQuery` (rewired to product-service) |
| **AuthCallback** | `App.jsx` | OAuth code exchange | `keycloakApi.useExchangeTokenMutation` |

---

## 9. Key Data Shape Differences

### Agent creation (UI → pbx-core)
```js
// UI sends:
{
  tenant_id: "uuid",
  subscription_id: "uuid",
  username: "agent1",
  sip_domain: "acme.sip.dalaillama.in",
  display_name: "Agent One",
  extension: "1001",
  email: "agent1@acme.com",
  role: "AGENT",
  skills: ["english", "billing"]
}
```

### Bot creation (UI → pbx-core)
```js
{
  tenant_id: "uuid",
  subscription_id: "uuid",
  name: "Support Bot",
  system_prompt: "You are a helpful support assistant...",
  greeting_message: "Hello! How can I help?",
  voice_provider: "google",
  voice_id: "en-US-Neural2-F",
  language: "en-US",
  max_turns: 20,
  escalation_rules: { max_failures: 3, action: "transfer", target: "queue-uuid" },
  transfer_target: "queue-uuid",
  transfer_type: "QUEUE"
}
```

### Subscription creation (UI → product-service)
```js
{
  tenantId: "uuid",
  productCode: "AI_CC",
  planCode: "AI_CC_STARTER",
  agentCount: 5,
  did: {
    number: "+911234567890",
    country: "IN",
    region: "Maharashtra",
    city: "Mumbai"
  }
}
```

---

## 10. Backend Endpoints Status

### ✅ Implemented (8 endpoints)

| Feature | UI Hook | Backend Endpoint | Service |
|---------|---------|------------------|---------|
| Public tenant config | `useTenantAuth` | `GET /api/v1/public/tenant-config/{slug}` | tenant-service:8091 |
| Tenant analytics | `useGetTenantAnalyticsQuery` | `GET /api/v1/billing/{tid}/analytics?days=30` | billing-service:8083 |
| Monthly billing summary | `useGetMonthlyBillingQuery` | `GET /api/v1/billing/{tid}/monthly?months=6` | billing-service:8083 |
| Invoice download | `useDownloadInvoiceMutation` | `GET /api/v1/billing/{tid}/invoice/{YYYY-MM}` | billing-service:8083 |
| AI Insights | `useGetAIInsightsQuery` | `GET /api/v1/supervisor/ai-insights?tenant_id=&days=30` | pbx-core:8080 |
| Agent leaderboard | `useGetAgentLeaderboardQuery` | `GET /api/v1/supervisor/leaderboard?tenant_id=&days=30` | pbx-core:8080 |
| Team metrics | `useGetTeamMetricsQuery` | `GET /api/v1/supervisor/team-metrics?tenant_id=` | pbx-core:8080 |
| Flagged calls | `useGetFlaggedCallsQuery` | `GET /api/v1/supervisor/flagged-calls?tenant_id=&days=30&threshold=-0.3` | pbx-core:8080 |

### ❌ Still Missing

| Feature | UI Hook | What's Needed |
|---------|---------|---------------|
| Purchase add-on | `usePurchaseAddonMutation` | Add-on subscription on product-service |
| Audit logs | `useGetAuditLogsQuery` | Audit log service (not yet built) |
| Call forecast | `useGetForecastQuery` | ML prediction (future) |
| Branding settings | `useGetBrandingSettingsQuery` | Tenant branding on tenant-service |
| Cloud connections | `useGetCloudConnectionsQuery` | Infrastructure management (platform-level) |

---

## 11. Rewiring Checklist

### Phase 1: Core Auth & Tenant Config ← START HERE
- [x] ~~Create `GET /api/v1/public/tenant-config/{slug}` in tenant-service~~ ✅ Done
- [ ] Uncomment `useTenantAuth('admin')` in admin-ui `App.jsx`
- [ ] Uncomment `useTenantAuth('agent')` in agents-ui `App.jsx`
- [ ] Update Vite proxy configs in all apps (see Section 2)
- [ ] Wire `tenantSlice` to read from the new tenant-config response shape

### Phase 2: Create `billingApi.js` (new RTK Query slice)
Split billing/analytics endpoints out of `apiSlice.js` into a dedicated slice:
```js
// shared/store/slices/billingApi.js
import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import { appConfig } from '@dalaillama/shared-config';

export const billingApi = createApi({
  reducerPath: 'billingApi',
  baseQuery: fetchBaseQuery({
    baseUrl: appConfig.API_BASE_URL,
    prepareHeaders: (headers) => {
      const token = localStorage.getItem('auth_token');
      if (token) headers.set('Authorization', `Bearer ${token}`);
      return headers;
    },
  }),
  tagTypes: ['Wallet', 'Billing', 'Usage', 'Transactions', 'Payments'],
  endpoints: (builder) => ({

    // ==================== WALLET ====================

    // GET /api/v1/billing/{tenantId}/wallet
    // Returns: { walletId, tenantId, balance, currency, creditLimit,
    //            lowBalanceThreshold, autoRechargeEnabled, autoRechargeThreshold,
    //            autoRechargeAmount, lastRechargedAt }
    getWallet: builder.query({
      query: ({ tenantId }) => `/billing/${tenantId}/wallet`,
      providesTags: ['Wallet'],
    }),

    // POST /api/v1/billing/{tenantId}/wallet/recharge
    // Body: { amount: number }  (min 1.00)
    // Returns: { paymentId, amount, status: "PENDING", message }
    rechargeWallet: builder.mutation({
      query: ({ tenantId, amount }) => ({
        url: `/billing/${tenantId}/wallet/recharge`,
        method: 'POST',
        body: { amount },
      }),
      invalidatesTags: ['Wallet'],
    }),

    // GET /api/v1/billing/{tenantId}/wallet/transactions?page=0&size=20
    // Returns: Page<{ id, type, amount, balanceBefore, balanceAfter, reference, description, createdAt }>
    getWalletTransactions: builder.query({
      query: ({ tenantId, page = 0, size = 20 }) =>
        `/billing/${tenantId}/wallet/transactions?page=${page}&size=${size}`,
      providesTags: ['Transactions'],
    }),

    // ==================== PAYMENTS ====================

    // POST /api/v1/billing/{tenantId}/payments
    // Body: { amount, description }
    // Returns: { paymentId, gatewayOrderId, amount, currency, status }
    createPayment: builder.mutation({
      query: ({ tenantId, amount, description }) => ({
        url: `/billing/${tenantId}/payments`,
        method: 'POST',
        body: { amount, description },
      }),
      invalidatesTags: ['Payments'],
    }),

    // POST /api/v1/billing/{tenantId}/payments/{paymentId}/verify
    // Body: { gatewayOrderId, gatewayPaymentId, gatewaySignature }
    // Returns: { paymentId, status: "SUCCESS", message }
    verifyPayment: builder.mutation({
      query: ({ tenantId, paymentId, gatewayOrderId, gatewayPaymentId, gatewaySignature }) => ({
        url: `/billing/${tenantId}/payments/${paymentId}/verify`,
        method: 'POST',
        body: { gatewayOrderId, gatewayPaymentId, gatewaySignature },
      }),
      invalidatesTags: ['Wallet', 'Payments', 'Transactions'],
    }),

    // GET /api/v1/billing/{tenantId}/payments
    getPayments: builder.query({
      query: ({ tenantId }) => `/billing/${tenantId}/payments`,
      providesTags: ['Payments'],
    }),

    // ==================== ANALYTICS & BILLING ====================

    getTenantAnalytics: builder.query({
      query: ({ tenantId, days = 30 }) => `/billing/${tenantId}/analytics?days=${days}`,
    }),
    getMonthlyBilling: builder.query({
      query: ({ tenantId, months = 6 }) => `/billing/${tenantId}/monthly?months=${months}`,
      providesTags: ['Billing'],
    }),
    downloadInvoice: builder.query({
      query: ({ tenantId, month }) => ({
        url: `/billing/${tenantId}/invoice/${month}`,
        responseHandler: (response) => response.blob(),
      }),
    }),
    getBillingState: builder.query({
      query: ({ tenantId }) => `/billing/${tenantId}/billing-state`,
      providesTags: ['Billing'],
    }),
  }),
});

export const {
  useGetWalletQuery,
  useRechargeWalletMutation,
  useGetWalletTransactionsQuery,
  useCreatePaymentMutation,
  useVerifyPaymentMutation,
  useGetPaymentsQuery,
  useGetTenantAnalyticsQuery,
  useGetMonthlyBillingQuery,
  useDownloadInvoiceQuery,
  useGetBillingStateQuery,
} = billingApi;
```

#### Wallet Balance + Add Balance — React Component
```jsx
// Example: apps/dashboard-ui/src/components/WalletCard.jsx
import { useState } from 'react';
import {
  useGetWalletQuery,
  useCreatePaymentMutation,
  useVerifyPaymentMutation,
} from '@dalaillama/shared-store/slices/billingApi';

const RAZORPAY_KEY = import.meta.env.VITE_RAZORPAY_KEY_ID;

export default function WalletCard({ tenantId }) {
  const { data: wallet, isLoading, refetch } = useGetWalletQuery({ tenantId });
  const [createPayment] = useCreatePaymentMutation();
  const [verifyPayment] = useVerifyPaymentMutation();
  const [amount, setAmount] = useState('');
  const [showInput, setShowInput] = useState(false);

  const handleAddBalance = async () => {
    const numAmount = parseFloat(amount);
    if (!numAmount || numAmount < 1) return;

    try {
      // 1. Create Razorpay order via backend
      const { paymentId, gatewayOrderId, currency } = await createPayment({
        tenantId,
        amount: numAmount,
        description: 'Wallet Recharge',
      }).unwrap();

      // 2. Open Razorpay checkout
      const options = {
        key: RAZORPAY_KEY,
        amount: numAmount * 100, // paise
        currency: currency || 'INR',
        order_id: gatewayOrderId,
        name: 'Dalai Llama',
        description: 'Wallet Recharge',
        handler: async (response) => {
          // 3. Verify payment on backend → credits wallet
          await verifyPayment({
            tenantId,
            paymentId,
            gatewayOrderId: response.razorpay_order_id,
            gatewayPaymentId: response.razorpay_payment_id,
            gatewaySignature: response.razorpay_signature,
          }).unwrap();
          setShowInput(false);
          setAmount('');
          refetch(); // refresh balance
        },
      };

      const rzp = new window.Razorpay(options);
      rzp.open();
    } catch (err) {
      console.error('Payment failed:', err);
    }
  };

  if (isLoading) return <div>Loading wallet...</div>;

  return (
    <div className="rounded-xl border bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm text-gray-500">Current Balance</p>
          <p className="text-3xl font-bold">
            {wallet?.currency === 'INR' ? '₹' : '$'}
            {Number(wallet?.balance ?? 0).toLocaleString('en-IN', {
              minimumFractionDigits: 2,
            })}
          </p>
        </div>
        {!showInput ? (
          <button
            onClick={() => setShowInput(true)}
            className="rounded-lg bg-indigo-600 px-4 py-2 text-white hover:bg-indigo-700"
          >
            + Add Balance
          </button>
        ) : (
          <div className="flex items-center gap-2">
            <input
              type="number"
              min="1"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="Amount"
              className="w-32 rounded-lg border px-3 py-2"
            />
            <button
              onClick={handleAddBalance}
              className="rounded-lg bg-green-600 px-4 py-2 text-white hover:bg-green-700"
            >
              Pay
            </button>
            <button
              onClick={() => { setShowInput(false); setAmount(''); }}
              className="rounded-lg border px-3 py-2 text-gray-600 hover:bg-gray-100"
            >
              Cancel
            </button>
          </div>
        )}
      </div>
      {wallet?.autoRechargeEnabled && (
        <p className="mt-2 text-xs text-gray-400">
          Auto-recharge: ₹{Number(wallet.autoRechargeAmount).toLocaleString()} when below
          ₹{Number(wallet.autoRechargeThreshold).toLocaleString()}
        </p>
      )}
    </div>
  );
}
```

> **Note:** Add `<script src="https://checkout.razorpay.com/v1/checkout.js"></script>` to your
> `index.html`, and set `VITE_RAZORPAY_KEY_ID` in your `.env`.

### Phase 3: Add supervisor endpoints to `pbxCoreApi.js`
The following endpoints are ready — add them to `pbxCoreApi.js`:
```js
// Add to existing pbxCoreApi endpoints:
getAIInsights: builder.query({
  query: ({ tenantId, days = 30 }) => `/supervisor/ai-insights?tenant_id=${tenantId}&days=${days}`,
}),
getAgentLeaderboard: builder.query({
  query: ({ tenantId, days = 30 }) => `/supervisor/leaderboard?tenant_id=${tenantId}&days=${days}`,
}),
getTeamMetrics: builder.query({
  query: ({ tenantId }) => `/supervisor/team-metrics?tenant_id=${tenantId}`,
}),
getFlaggedCalls: builder.query({
  query: ({ tenantId, days = 30, threshold = -0.3 }) =>
    `/supervisor/flagged-calls?tenant_id=${tenantId}&days=${days}&threshold=${threshold}`,
}),
```

### Phase 4: Switch admin-ui pages to `pbxCoreApi.js`
- [ ] admin-ui `Agents.jsx` → use `pbxCoreApi` hooks
- [ ] admin-ui `Queues.jsx` → use `pbxCoreApi` hooks
- [ ] admin-ui `Bots.jsx` → use `pbxCoreApi` hooks
- [ ] admin-ui `Campaigns.jsx` → use `pbxCoreApi` hooks
- [ ] admin-ui `Routing.jsx` → use `pbxCoreApi` hooks
- [ ] agents-ui `Console.jsx` → use `pbxCoreApi` + `useSipPhone` + `useStompEvents`

### Phase 5: Rewire `apiSlice.js` for Non-PBX Endpoints
- [ ] Fix tenant CRUD URLs (`/tenants` not `/tenants/list`)
- [ ] Fix product/plan URLs (product-service paths)
- [ ] Fix subscription URLs (product-service paths)
- [ ] Fix DID URLs → `POST /api/v1/tenants/{tid}/dids`
- [ ] Move billing hooks to `billingApi.js` (Phase 2)
- [ ] Fix compliance URLs → `GET/PUT /api/v1/tenants/{tid}/compliance`
- [ ] Add `tenantId` path parameter to all tenant-scoped queries

### Phase 6: WebSocket & SIP
- [ ] Verify STOMP connection with real pbx-core
- [ ] Verify SIP registration with Kamailio
- [ ] Test inbound/outbound call flow end-to-end

### Phase 7: Supervisor UI
- [ ] Build dashboard page using `useGetSupervisorDashboardQuery`
- [ ] Build AI insights panel using `useGetAIInsightsQuery`
- [ ] Build agent leaderboard using `useGetAgentLeaderboardQuery`
- [ ] Build team metrics panel using `useGetTeamMetricsQuery`
- [ ] Build flagged calls table using `useGetFlaggedCallsQuery`
- [ ] Wire listen/whisper/barge buttons

---

## 12. Suggestions & Observations

### Issues Found

1. **`apiSlice.js` is bloated with duplicate endpoints** — many endpoints point to `/queue/{userId}` or `/agent/{userId}` as placeholders. Clean these up.

2. **No `tenantId` in apiSlice queries** — Most billing/product endpoints need `tenantId` in the URL path, but `apiSlice` passes it as a generic `userId`. Refactor to use `tenantId` from Redux state.

3. **Two competing agent API patterns** — `pbxCoreApi.js` correctly maps to `pbx-core:8080/api/v1/agents`, while `apiSlice.js` has stale `/agent/add`, `/agent/update`, `/agent/disable` that hit the wrong service. **Use only `pbxCoreApi.js`** for PBX operations.

4. **Mock auth in admin-ui and agents-ui** — Both apps have `useTenantAuth` commented out with hardcoded mock objects. This is fine for dev but must be switched back for integration.

5. **SIP trunks have two homes** — `pbx-core` has `TrunkController` (runtime config) and `product-service` has `SipTrunkController` (provisioned infrastructure). UI should use pbx-core for management, product-service for initial provisioning.

### Recommendations

1. **Create a shared `useResolvedTenantId()` hook** that reads `tenantId` from `tenantSlice` and injects it into all API calls. This avoids passing `tenantId` everywhere manually.

2. **Split `apiSlice.js` into domain-specific API slices**:
   - `tenantApi.js` → tenant-service
   - `productApi.js` → product-service
   - `billingApi.js` → billing-service
   - Keep `pbxCoreApi.js` as-is

3. **Add RTK Query tag invalidation** to `apiSlice.js` endpoints (currently missing). `pbxCoreApi.js` already has proper tags.

4. **Feature gating** — `tenantSlice.features` can gate UI sections (e.g., hide Campaigns page if `campaigns: false`). The `selectFeature(name)` selector is already built.

5. **WebSocket reconnection** — `useStompEvents` has 5s reconnect delay — good. But add a visual indicator (red dot) in the UI when disconnected.

---

## 13. Auto-Provisioning & Real-Time Progress (WebSocket)

### Flow
After a user purchases a subscription on **platform-ui**, provisioning starts **automatically**:

```
platform-ui: User buys plan
    → product-service: processes payment
    → POST /api/v1/internal/tenants/{tenantId}/activate (no JWT, internal)
    → tenant-service: creates TenantApp, transitions tenant to PROVISIONING
    → ProvisioningOrchestrator runs 15 steps async
    → WebSocket events pushed to dashboard-ui in real-time
    → On success: tenant transitions to ACTIVE
    → On failure: tenant transitions to PROVISIONING_FAILED
```

### Tenant State Machine

```
CREATED → IDENTITY_CREATED → WALLET_CREATED → PROVISIONING → ACTIVE
                                                    ↓              ↓
                                          PROVISIONING_FAILED   SUSPENDED
                                                    ↓              ↓
                                              (retry) → PROVISIONING   ACTIVE
```

### WebSocket Events (STOMP)

Subscribe to `/topic/tenant/{tenantId}/provisioning` for real-time progress:

| Event | Payload | When |
|-------|---------|------|
| `STARTED` | `{ step: "Fetch plan entitlements" }` | Provisioning begins (or resumes) |
| `STEP_RUNNING` | `{ step: "Configure Kamailio via PBX-Core (retry 2)" }` | Each step starts |
| `STEP_COMPLETED` | `{ step: "Configure Kamailio via PBX-Core" }` | Each step succeeds |
| `FAILED` | `{ step: "Configure Kamailio...", error: "..." }` | Step failed after 3 retries |
| `COMPLETED` | `{ message: "All provisioning steps completed" }` | All 15 steps done |

Also subscribe to `/topic/tenant/{tenantId}/apps` for the initial `SUBSCRIPTION_ACTIVATED` event.

### Provisioning Steps (15 total)

| # | Step | Description |
|---|------|-------------|
| 1 | `FETCH_ENTITLEMENTS` | Fetch plan features from product-service |
| 2 | `RESOLVE_NAMESPACE` | Determine shared vs dedicated namespace |
| 3 | `CREATE_KEYCLOAK_CLIENT` | Create OIDC client in tenant realm |
| 4 | `CREATE_ADMIN_USER` | Create tenant admin in Keycloak |
| 5 | `CONFIGURE_KAMAILIO` | SIP routing via PBX-Core |
| 6 | `CONFIGURE_FREESWITCH` | Generate & store XML dialplan |
| 7 | `CONFIGURE_COTURN` | TURN credentials for WebRTC |
| 8 | `CONFIGURE_RTPENGINE` | RTP media relay + AI fork |
| 9 | `CONFIGURE_AI_SERVICE` | Voice-brain AI pipeline config |
| 10 | `STAMP_INFRA_URLS` | SIP/WSS/ESL URLs into TenantApp |
| 11 | `CONFIGURE_ISTIO_ROUTES` | Istio VirtualService for tenant |
| 12 | `CONFIGURE_MINIO_BUCKETS` | Recording/voicemail storage |
| 13 | `SYNC_PBX_CORE` | Reload Kamailio + FreeSWITCH |
| 14 | `HEALTH_CHECK` | Verify endpoints reachable |
| 15 | `FINALIZE` | Mark tenant ACTIVE |

### Retry / Failover

- **Per-step retry**: Each step retries up to 3 times with linear backoff (2s, 4s, 6s)
- **Auto-recovery**: Scheduler runs every 5 min, picks up RUNNING tasks stuck >15 min (pod crash recovery)
- **Auto-retry**: FAILED tasks are auto-retried up to 3 times with 10 min spacing
- **Manual retry**: `POST /api/v1/tenants/apps/{tenantAppId}/provision` — resumes from the failed step
- **Distributed lock**: Prevents concurrent provisioning of the same app

### Dashboard UI Integration

```js
// dashboard-ui — Provisioning progress component
// Subscribe to STOMP topic after SUBSCRIPTION_ACTIVATED event

const { tenantId, tenantAppId } = subscriptionData;

// 1. Show progress bar with 15 steps
stompClient.subscribe(`/topic/tenant/${tenantId}/provisioning`, (msg) => {
  const { event, step, error } = JSON.parse(msg.body);
  // event: STARTED | STEP_RUNNING | STEP_COMPLETED | FAILED | COMPLETED
  updateProgressBar(event, step, error);
});

// 2. Poll status API as fallback (WebSocket disconnect)
// GET /api/v1/tenants/apps/{tenantAppId}/provision/status
// GET /api/v1/tenants/apps/{tenantAppId}/provision/logs

// 3. On FAILED — show "Retry" button
// POST /api/v1/tenants/apps/{tenantAppId}/provision
```

---

## 14. Kafka Topic Matrix

All topics are pre-created in `infra-platform/charts/infra/values.yaml`. Services must NOT rely on auto-create.

### Tenant Lifecycle (tenant-service → all)

| Topic | Producer | Consumer(s) | Payload |
|-------|----------|-------------|---------|
| `tenant.created` | tenant-service | product-service, billing-service | `{tenantId, slug}` |
| `tenant.activated` | tenant-service | product-service | `{tenantId}` |
| `tenant.suspended` | tenant-service | product-service | `{tenantId}` |
| `tenant.deleted` | tenant-service | product-service, billing-service | `{tenantId}` |
| `tenant.state.changed` | tenant-service | (broadcast) | `{tenantId, oldState, newState, message}` |

### Product & Subscription (product-service → tenant-service)

| Topic | Producer | Consumer(s) | Payload |
|-------|----------|-------------|---------|
| `product.plan.assigned` | product-service | tenant-service | `{tenantId, planId, planCode, planTier, subscriptionId}` |
| `product.plan.changed` | product-service | — | `{tenantId, planId}` |
| `product.did.purchased` | product-service | tenant-service | `{tenantId, didId, didNumber, didCountry}` |
| `product.did.provisioned` | product-service | billing-service | `{tenantId, didId}` |
| `product.did.released` | product-service | billing-service | `{tenantId, didId}` |
| `product.subscription.activated` | product-service | tenant-service | `{tenantId, subscriptionId}` |

### Billing (billing-service → tenant-service)

| Topic | Producer | Consumer(s) | Payload |
|-------|----------|-------------|---------|
| `billing.wallet.created` | billing-service | tenant-service | `{tenantId, walletId}` |
| `billing.wallet.funded` | billing-service | tenant-service | `{tenantId}` |
| `billing.wallet.low` | billing-service | — | `{tenantId, balance}` |
| `billing.state.changed` | billing-service | tenant-service | `{tenantId, state}` |
| `billing.payment.received` | billing-service | — | `{tenantId, amount}` |
| `billing.cdr.rated` | billing-service | — | `{tenantId, callId, cost}` |
| `billing.subscription.activated` | billing-service | — | `{tenantId, subscriptionId}` |

### Call / CDR (pbx-core → billing-service)

| Topic | Producer | Consumer(s) | Payload |
|-------|----------|-------------|---------|
| `call.billing` | pbx-core CdrService | billing-service | `{tenantId, callId, direction, billableSeconds, cost, ratePerMinute, aiMinutes}` |
| `call.cdr` | pbx-core CdrService | — | `{tenantId, callId, direction, status, duration, hangupCause}` |
| `cdr.completed` | pbx-core | — | CDR completion event |

### Telecom / SIP (Kamailio/FreeSWITCH → agent-service)

| Topic | Producer | Consumer(s) | Env Var |
|-------|----------|-------------|---------|
| `pbx-registration-events` | Kamailio | agent-service | `KAFKA_REG_TOPIC` |
| `pbx-call-events` | Kamailio/FreeSWITCH | agent-service | `KAFKA_CALL_TOPIC` |
| `rtp-voice-frames` | RTPEngine | agent-service, ai-service | `RTP_TOPIC` |
| `ai-transcript-results` | ai-service | agent-service | `AI_RESULT_TOPIC` |

### Agent (agent-service — dynamic per-tenant)

| Topic Pattern | Direction | Purpose |
|---------------|-----------|---------|
| `agent.assign.requests` | consumed | Assignment requests from pbx-core |
| `agent.assign.responses` | produced | Assignment decisions back to pbx-core |
| `agent.events.{tenantId}` | produced | Agent status events (dynamic) |
| `agent.audio.out.{tenantId}` | produced | Agent audio output (dynamic) |
| `agent.commands.{tenantId}` | produced | Agent control commands (dynamic) |

### Recording (blob-manager — dynamic per-tenant)

| Topic Pattern | Producer | Purpose |
|---------------|----------|---------|
| `cdr.recording.{tenantId}` | blob-manager | Recording metadata events (dynamic, created on-demand) |

### Provisioning

| Topic | Producer | Consumer(s) |
|-------|----------|-------------|
| `provisioning.step.completed` | tenant-service | — |
| `provisioning.failed` | tenant-service | — |

> **Note**: Dynamic per-tenant topics (`agent.events.*`, `agent.audio.out.*`, `agent.commands.*`, `cdr.recording.*`) are created on-demand by Kafka auto-create. Only static topics are pre-created in infra.

> **Env vars for telecom topics** must be set in Helm values for agent-service and ai-service deployments:
> `KAFKA_REG_TOPIC=pbx-registration-events`, `KAFKA_CALL_TOPIC=pbx-call-events`, `RTP_TOPIC=rtp-voice-frames`, `AI_RESULT_TOPIC=ai-transcript-results`

---

## 15. Deployment Notes

### Should You Deploy Now?

**Yes, the backend is deployment-ready.** All 8 new endpoints are additive (no breaking changes to existing APIs). Here's the deploy checklist:

### Pre-Deploy Checklist

| Item | Notes |
|------|-------|
| ✅ No DB migrations needed | All new endpoints use existing tables/columns |
| ✅ No new dependencies | No new Maven/Gradle deps added |
| ✅ No env vars needed | No new config required |
| ✅ Backward compatible | All new endpoints, no changes to existing ones |
| ⚠️ SecurityConfig change | tenant-service: `/api/v1/public/**` now `permitAll` — intentional for pre-auth bootstrap |

### Deploy Order (safe)
1. **billing-service** — new `AnalyticsController`, updated `UsageRecordRepository` + `TransactionRepository`
2. **tenant-service** — new `PublicTenantConfigController`, updated `SecurityConfig`
3. **pbx-core** — updated `SupervisorService`, `SupervisorController`, `CallRecordRepository`

All three can be deployed independently and in any order — no cross-service dependencies between the new endpoints.

### Quick Smoke Test (after deploy)
```bash
# 1. Public tenant config (no auth)
curl http://localhost:8091/api/v1/public/tenant-config/{your-slug}

# 2. Tenant analytics (needs JWT)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8083/api/v1/billing/{tid}/analytics?days=30

# 3. Monthly billing
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8083/api/v1/billing/{tid}/monthly?months=3

# 4. Supervisor endpoints (needs JWT)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/supervisor/ai-insights?tenant_id={tid}&days=30

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/supervisor/leaderboard?tenant_id={tid}&days=30

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/supervisor/team-metrics?tenant_id={tid}

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/supervisor/flagged-calls?tenant_id={tid}&days=30
```
