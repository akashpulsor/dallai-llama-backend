package com.dalai.llama.creativeplanning.domain;

/** COMPANY tenants subscribe to a plan and fund a wallet (llm-gateway's existing wallet-guard
 * already covers generation cost -- no separate per-project payment gate). AI_VIDEO_CREATOR
 * tenants have no subscription and pay per-project via a shareable requirement page instead. */
public enum TenantType {
    COMPANY,
    AI_VIDEO_CREATOR
}
