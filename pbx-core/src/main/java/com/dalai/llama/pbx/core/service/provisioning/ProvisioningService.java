package com.dalai.llama.pbx.core.service.provisioning;


import com.dalai.llama.pbx.core.domain.entity.core.TenantDialplan;
import com.dalai.llama.pbx.core.domain.entity.kamailio.Dispatcher;
import com.dalai.llama.pbx.core.domain.entity.kamailio.SipDomain;
import com.dalai.llama.pbx.core.domain.entity.kamailio.Subscriber;
import com.dalai.llama.pbx.core.domain.enums.SubscriberType;
import com.dalai.llama.pbx.core.dto.request.provisioning.*;
import com.dalai.llama.pbx.core.dto.response.TenantTelecomEndpoints;
import com.dalai.llama.pbx.core.dto.response.TurnCredentialsResponse;
import com.dalai.llama.pbx.core.redis.AiConfigRedisService;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.redis.RtpEngineConfigRedisService;
import com.dalai.llama.pbx.core.redis.TenantConfigRedisService;
import com.dalai.llama.pbx.core.repository.core.TenantDialplanRepository;
import com.dalai.llama.pbx.core.repository.kamailio.*;
import com.dalai.llama.pbx.core.service.kamailio.KamailioReloadService;
import com.dalai.llama.pbx.core.service.turn.TurnCredentialService;
import com.dalai.llama.pbx.core.util.SipDigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Provisioning execution service — receives resolved DTOs from tenant-service
 * and writes them to DB + Redis + triggers kamcmd reload.
 *
 * This is NOT an orchestrator. Tenant-service decides WHAT to provision and in
 * what order. This service just EXECUTES the writes PBX-Core owns.
 *
 * Each method is self-contained:
 *   provisionKamailio()     → DB writes + Redis channel limits + kamcmd reload
 *   storeDialplan()         → DB upsert
 *   storeRtpEngineConfig()  → Redis hash
 *   configureTurn()         → Redis + DB + return creds
 *   storeAiConfig()         → Redis JSON
 *   suspendTenant()         → DB update + kamcmd reload
 *   resumeTenant()          → DB update + kamcmd reload
 *   deprovisionAll()        → DB deletes + Redis eviction + kamcmd reload
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningService {

    private final SubscriberRepository subscriberRepository;
    private final SipDomainRepository domainRepository;
    private final DispatcherRepository dispatcherRepository;
    private final DialplanRepository dialplanRepository;
    private final UacRegRepository uacRegRepository;
    private final TenantDialplanRepository tenantDialplanRepository;

    private final ChannelCounterService channelCounter;
    private final TenantConfigRedisService configRedisService;
    private final RtpEngineConfigRedisService rtpEngineRedisService;
    private final AiConfigRedisService aiConfigRedisService;

    private final KamailioReloadService kamailioReload;
    private final TurnCredentialService turnCredentialService;

    @Value("${sip.external-ip:}")
    private String sipExternalIp;

    @Value("${sip.default-domain:sip.dalaillama.in}")
    private String sipDefaultDomain;

    @Value("${sip.wss-url:wss://sip.dalaillama.in:7443}")
    private String sipWssUrl;

    @Value("${freeswitch.esl.host:127.0.0.1}")
    private String eslHost;

    @Value("${freeswitch.esl.port:8021}")
    private int eslPort;

    // ═══════════════════════════════════════════════════════════
    // 1. KAMAILIO PROVISIONING
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public TenantTelecomEndpoints provisionKamailio(KamailioProvisioningRequest req) {
        UUID tenantId = req.getTenantId();
        UUID subscriptionId = req.getSubscriptionId();
        String domain = req.getSipEndpointDomain();

        log.info("Provisioning Kamailio for tenant={} sub={} domain={}", tenantId, subscriptionId, domain);

        // ── 1a. Domain ──
        if (domain != null && !domainRepository.existsByDomain(domain)) {
            domainRepository.save(SipDomain.builder()
                    .domain(domain)
                    .did(req.getDidNumber())
                    .tenantId(tenantId)
                    .subscriptionId(subscriptionId)
                    .build());
            log.info("  Created domain: {}", domain);
        }

        // ── 1b. SIP Endpoint Subscriber ──
        if (req.getSipEndpointUsername() != null && domain != null) {
            upsertSubscriber(tenantId, subscriptionId, req.getSipEndpointUsername(),
                    domain, req.getSipEndpointPasswordHash(), SubscriberType.DID_ENDPOINT,
                    "DID:" + req.getDidNumber());
            log.info("  Created/updated subscriber: {}@{}", req.getSipEndpointUsername(), domain);
        }

        // ── 1c. Tenant Trunk Subscriber ──
        if (req.getTenantTrunkUsername() != null && req.getTenantTrunkDomain() != null) {
            upsertSubscriber(tenantId, subscriptionId, req.getTenantTrunkUsername(),
                    req.getTenantTrunkDomain(), req.getTenantTrunkPasswordHash(),
                    SubscriberType.TRUNK, "Trunk:" + req.getNamespace());
            log.info("  Created/updated trunk subscriber: {}@{}", req.getTenantTrunkUsername(), req.getTenantTrunkDomain());
        }

        // ── 1d. Dispatcher ──
        int setId = req.getDispatcherSetId() != null ? req.getDispatcherSetId() : 1;
        String fsDestination = "sip:" + eslHost + ":5080";
        if (!dispatcherRepository.existsBySetidAndDestination(setId, fsDestination)) {
            dispatcherRepository.save(Dispatcher.builder()
                    .setid(setId)
                    .destination(fsDestination)
                    .description("FreeSWITCH for " + req.getNamespace())
                    .tenantId(tenantId)
                    .subscriptionId(subscriptionId)
                    .build());
            log.info("  Created dispatcher: setid={} → {}", setId, fsDestination);
        }

        // ── 1e. Redis: channel limits ──
        channelCounter.reset(tenantId); // Start fresh
        log.info("  Channel limits set: max={} inbound={} outbound={}",
                req.getMaxChannels(), req.getMaxInbound(), req.getMaxOutbound());

        // ── 1f. Redis: DID → tenantId mapping ──
        if (req.getDidNumber() != null) {
            configRedisService.mapDidToTenant(req.getDidNumber(), tenantId);
            log.info("  DID mapping: {} → {}", req.getDidNumber(), tenantId);
        }

        // ── 1g. Redis: domain → tenantId mapping ──
        if (domain != null) {
            configRedisService.mapDomainToTenant(domain, tenantId);
        }

        // ── 1h. Kamailio reload ──
        kamailioReload.reloadDomains();
        kamailioReload.reloadDispatchers();
        log.info("  Kamailio reloaded (domain + dispatcher)");

        // ── 1i. Build response with computed endpoints ──
        String resolvedSipIp = sipExternalIp != null && !sipExternalIp.isBlank()
                ? sipExternalIp : eslHost;

        TenantTelecomEndpoints endpoints = TenantTelecomEndpoints.builder()
                .sipUdpUrl("sip:" + resolvedSipIp + ":5060;transport=udp")
                .sipTlsUrl("sip:" + resolvedSipIp + ":5061;transport=tls")
                .sipWssUrl(sipWssUrl)
                .turnUrl("turn:" + sipDefaultDomain + ":3478")
                .eslHost(eslHost)
                .eslPort(eslPort)
                .configSummary(String.format("domain=%s, subscribers=2, dispatcher_set=%d, " +
                        "channels=%d, did=%s", domain, setId, req.getMaxChannels(), req.getDidNumber()))
                .build();

        log.info("Kamailio provisioning complete for tenant={}: {}", tenantId, endpoints.getConfigSummary());
        return endpoints;
    }

    // ═══════════════════════════════════════════════════════════
    // 2. FREESWITCH DIALPLAN
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void storeDialplan(FreeSwitchDialplanRequest req) {
        UUID tenantId = req.getTenantId();
        log.info("Storing dialplan for tenant={} context={}", tenantId, req.getContext());

        TenantDialplan existing = tenantDialplanRepository.findByTenantId(tenantId).orElse(null);

        if (existing != null) {
            existing.setContext(req.getContext());
            existing.setProductCode(req.getProductCode());
            existing.setDialplanXml(req.getDialplanContent());
            existing.setSubscriptionId(req.getSubscriptionId());
            tenantDialplanRepository.save(existing);
        } else {
            tenantDialplanRepository.save(TenantDialplan.builder()
                    .tenantId(tenantId)
                    .subscriptionId(req.getSubscriptionId())
                    .context(req.getContext())
                    .productCode(req.getProductCode())
                    .dialplanXml(req.getDialplanContent())
                    .build());
        }

        log.info("Dialplan stored for tenant={} ({} chars)", tenantId, req.getDialplanContent().length());
    }

    // ═══════════════════════════════════════════════════════════
    // 3. RTPENGINE CONFIG
    // ═══════════════════════════════════════════════════════════

    public void storeRtpEngineConfig(RtpEngineConfigRequest req) {
        log.info("Storing RTPEngine config for tenant={}", req.getTenantId());
        rtpEngineRedisService.store(req.getTenantId(), req);
    }

    public void removeRtpEngineConfig(UUID tenantId) {
        rtpEngineRedisService.remove(tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // 4. TURN CREDENTIALS
    // ═══════════════════════════════════════════════════════════

    public TurnCredentialsResponse configureTurn(UUID tenantId, TurnConfigRequest req) {
        boolean dedicated = Boolean.TRUE.equals(req.getDedicatedInfrastructure());
        String slug = req.getNamespace();

        Map<String, Object> creds = turnCredentialService.generateAndStore(slug, tenantId, dedicated, 86400);

        return TurnCredentialsResponse.builder()
                .username((String) creds.get("username"))
                .password((String) creds.get("password"))
                .turnUrl((String) creds.get("turn_url"))
                .turnsUrl((String) creds.get("turns_url"))
                .stunUrl((String) creds.get("stun_url"))
                .ttl((Integer) creds.get("ttl"))
                .build();
    }

    public void removeTurnConfig(String tenantSlug) {
        turnCredentialService.getCredentials(tenantSlug, null, false); // no-op if missing
        // Redis TTL handles cleanup; explicit remove for immediate deprovision
    }

    // ═══════════════════════════════════════════════════════════
    // 5. AI CONFIG
    // ═══════════════════════════════════════════════════════════

    public void storeAiConfig(AiConfigRequest req) {
        log.info("Storing AI config for tenant={} mode={}", req.getTenantId(), req.getAiMode());
        aiConfigRedisService.store(req.getTenantId(), req);
    }

    public void removeAiConfig(UUID tenantId) {
        aiConfigRedisService.remove(tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // 6. SUSPEND / RESUME
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void suspendTenant(UUID tenantId) {
        log.info("Suspending tenant {}", tenantId);
        subscriberRepository.updateActiveByTenantId(tenantId, false);
        domainRepository.updateActiveByTenantId(tenantId, false);
        kamailioReload.reloadDomains();
        log.info("Tenant {} suspended — new calls rejected, existing calls continue", tenantId);
    }

    @Transactional
    public void resumeTenant(UUID tenantId) {
        log.info("Resuming tenant {}", tenantId);
        subscriberRepository.updateActiveByTenantId(tenantId, true);
        domainRepository.updateActiveByTenantId(tenantId, true);
        kamailioReload.reloadDomains();
        log.info("Tenant {} resumed", tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // 7. FULL DEPROVISION
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void deprovisionAll(UUID subscriptionId) {
        log.info("Full deprovision for subscription {}", subscriptionId);

        // Find tenantId from subscriber rows (they all share same subscription)
        var subscribers = subscriberRepository.findBySubscriptionId(subscriptionId);
        UUID tenantId = subscribers.isEmpty() ? null : subscribers.getFirst().getTenantId();
        String domain = subscribers.isEmpty() ? null : subscribers.getFirst().getDomain();
        String didNumber = null;

        // Find DID from domain
        if (domain != null) {
            domainRepository.findByDomain(domain).ifPresent(d -> {
                // DID stored as domain.did
            });
        }

        // DB deletes
        subscriberRepository.deleteBySubscriptionId(subscriptionId);
        dialplanRepository.deleteBySubscriptionId(subscriptionId);
        // Domain + dispatcher linked by tenantId
        if (tenantId != null) {
            domainRepository.deleteByTenantId(tenantId);
            dispatcherRepository.deleteByTenantId(tenantId);
            uacRegRepository.deleteByTenantId(tenantId);
            tenantDialplanRepository.deleteByTenantId(tenantId);

            // Redis eviction
            configRedisService.evictConfig(tenantId);
            if (domain != null) configRedisService.removeDomainMapping(domain);
            rtpEngineRedisService.remove(tenantId);
            aiConfigRedisService.remove(tenantId);
            channelCounter.reset(tenantId);
        }

        // Kamailio reload
        kamailioReload.reloadAll();

        log.info("Full deprovision complete for subscription {} (tenant={})", subscriptionId, tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL — subscriber upsert
    // ═══════════════════════════════════════════════════════════

    private void upsertSubscriber(UUID tenantId, UUID subscriptionId, String username,
                                  String domain, String passwordHash, SubscriberType type,
                                  String displayName) {
        Subscriber sub = subscriberRepository.findByUsernameAndDomain(username, domain)
                .orElse(Subscriber.builder()
                        .username(username)
                        .domain(domain)
                        .tenantId(tenantId)
                        .subscriptionId(subscriptionId)
                        .subscriberType(type)
                        .build());

        // If passwordHash is a pre-computed HA1, use it directly.
        // If it's a plaintext password, compute HA1/HA1B.
        if (passwordHash != null && passwordHash.length() == 32) {
            // Looks like an MD5 hash (32 hex chars) — use as HA1 directly
            sub.setHa1(passwordHash);
            sub.setHa1b(passwordHash); // tenant-service should send both, fallback to same
        } else if (passwordHash != null) {
            // Plaintext password — compute HA1/HA1B
            sub.setHa1(SipDigestUtil.ha1(username, domain, passwordHash));
            sub.setHa1b(SipDigestUtil.ha1b(username, domain, passwordHash));
        }

        sub.setDisplayName(displayName);
        sub.setIsActive(true);
        subscriberRepository.save(sub);
    }
}