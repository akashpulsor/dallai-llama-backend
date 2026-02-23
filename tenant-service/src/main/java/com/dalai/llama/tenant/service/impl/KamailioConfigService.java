package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KamailioConfigService {

    @Qualifier("kamailioJdbcTemplate")
    private final JdbcTemplate kamailioJdbc;

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.shared.freeswitch.host:freeswitch.dalaillama.svc.cluster.local}")
    private String sharedFreeswitchHost;

    @Value("${services.tenant.internal-url:http://tenant-service:8091}")
    private String tenantServiceUrl;

    public String configureForSubscription(TenantApp app, PlanEntitlementResponse entitlements) {
        log.info("Configuring Kamailio for subscription {} - product: {}",
                app.getSubscriptionId(), app.getProductCode());

        String tenantContext = "tenant_" + app.getNamespace();

        if (app.getSipEndpointUsername() != null) {
            addSubscriber(app.getSipEndpointUsername(), app.getSipEndpointPasswordHash(),
                    app.getSipEndpointDomain(), app.getSubscriptionId().toString(), "DID_ENDPOINT");
        }

        if (app.getTenantTrunkUsername() != null) {
            addSubscriber(app.getTenantTrunkUsername(), app.getTenantTrunkPasswordHash(),
                    app.getTenantTrunkDomain(), app.getSubscriptionId().toString(), "TENANT_TRUNK");
        }

        if (app.getDidNumber() != null) {
            addDialplanEntry(app, entitlements, tenantContext);
        }

        addDispatcherEntry(app);
        storeChannelLimits(app, entitlements);

        return generateKamailioConfig(app, entitlements);
    }

    private void addSubscriber(String username, String ha1, String domain, String subscriptionId, String type) {
        try {
            Integer count = kamailioJdbc.queryForObject(
                    "SELECT COUNT(*) FROM subscriber WHERE username = ? AND domain = ?",
                    Integer.class, username, domain);

            if (count != null && count > 0) {
                kamailioJdbc.update(
                        "UPDATE subscriber SET ha1 = ?, ha1b = ? WHERE username = ? AND domain = ?",
                        ha1, ha1, username, domain);
            } else {
                kamailioJdbc.update("""
                    INSERT INTO subscriber (id, username, domain, password, ha1, ha1b, rpid)
                    VALUES (?, ?, ?, '', ?, ?, ?)
                    """, UUID.randomUUID().hashCode(), username, domain, ha1, ha1,
                        subscriptionId + ":" + type);
                log.info("Added subscriber: {}@{} ({})", username, domain, type);
            }
        } catch (Exception e) {
            log.error("Failed to add subscriber {}@{}: {}", username, domain, e.getMessage());
        }
    }

    private void addDialplanEntry(TenantApp app, PlanEntitlementResponse e, String tenantContext) {
        String didNumber = app.getDidNumber().replace("+", "");
        String productCode = app.getProductCode();

        String routingTarget = switch (productCode) {
            case "AI_CC" -> "ai_contact_center";
            case "CONV_IVR" -> "conversational_ivr";
            case "BASIC_PBX" -> "basic_pbx";
            case "OUTBOUND_DIALER" -> "dialer_inbound";
            case "VIRTUAL_RECEPTIONIST" -> "virtual_receptionist";
            default -> "default_ivr";
        };

        // ═══════════════════════════════════════════════════════════════
        // ADDED: auth_url for pre-call authorization
        // ═══════════════════════════════════════════════════════════════
        String authUrl = tenantServiceUrl + "/api/v1/internal/calls/authorize/inbound";

        String attrs = String.format(
                "subscription_id=%s;tenant_id=%s;product=%s;namespace=%s;" +
                        "max_channels=%d;direction=%s;ai_enabled=%s;recording=%s;" +
                        "auth_url=%s;rate_inbound=%s;rate_outbound=%s",
                app.getSubscriptionId(),
                app.getTenant().getId(),
                productCode,
                app.getNamespace(),
                app.getChannelTotal() != null ? app.getChannelTotal() : 30,
                app.getChannelDirection() != null ? app.getChannelDirection() : "BOTH",
                e.aiBotEnabled(),
                e.recordingEnabled(),
                authUrl,
                e.ratePerMinuteInbound() != null ? e.ratePerMinuteInbound() : "1.5",
                e.ratePerMinuteOutbound() != null ? e.ratePerMinuteOutbound() : "1.5"
        );

        String rewriteExp = String.format("%s@%s", tenantContext, routingTarget);

        try {
            kamailioJdbc.update("""
                INSERT INTO dialplan (dpid, pr, match_op, match_exp, match_len, subst_exp, repl_exp, attrs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (dpid, pr, match_exp) DO UPDATE SET repl_exp = EXCLUDED.repl_exp, attrs = EXCLUDED.attrs
                """, 1, 1, 1, didNumber, didNumber.length(), "", rewriteExp, attrs);
            log.info("Added dialplan: {} -> {} ({}) with auth_url", didNumber, rewriteExp, productCode);
        } catch (Exception ex) {
            log.error("Failed to add dialplan: {}", ex.getMessage());
        }
    }

    private void addDispatcherEntry(TenantApp app) {
        int setId;
        String destination;

        if (Boolean.TRUE.equals(app.getDedicatedInfrastructure())) {
            setId = 100 + Math.abs(app.getSubscriptionId().hashCode() % 900);
            destination = String.format("sip:freeswitch.%s.svc.cluster.local:5060", app.getNamespace());
        } else {
            setId = 1;
            destination = "sip:" + sharedFreeswitchHost + ":5060";
        }

        try {
            Integer count = kamailioJdbc.queryForObject(
                    "SELECT COUNT(*) FROM dispatcher WHERE setid = ? AND destination = ?",
                    Integer.class, setId, destination);

            if (count == null || count == 0) {
                kamailioJdbc.update("""
                    INSERT INTO dispatcher (setid, destination, flags, priority, attrs, description)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, setId, destination, 0, 0, "weight=100", "FreeSWITCH for " + app.getNamespace());
                log.info("Added dispatcher: setid={}, dest={}", setId, destination);
            }
        } catch (Exception e) {
            log.error("Failed to add dispatcher: {}", e.getMessage());
        }
    }

    private void storeChannelLimits(TenantApp app, PlanEntitlementResponse e) {
        String key = "kamailio:channels:" + app.getTenant().getId();
        redisTemplate.opsForHash().put(key, "max_total", String.valueOf(e.maxPstnChannels()));
        redisTemplate.opsForHash().put(key, "max_inbound",
                String.valueOf(app.getChannelInbound() != null ? app.getChannelInbound() : e.maxPstnChannels()));
        redisTemplate.opsForHash().put(key, "max_outbound",
                String.valueOf(app.getChannelOutbound() != null ? app.getChannelOutbound() : e.maxPstnChannels()));
        redisTemplate.opsForHash().put(key, "current_inbound", "0");
        redisTemplate.opsForHash().put(key, "current_outbound", "0");
        redisTemplate.expire(key, 30, TimeUnit.DAYS);

        // Subscription lookup for quick CDR enrichment
        String subKey = "kamailio:subscription:" + app.getSubscriptionId();
        redisTemplate.opsForHash().put(subKey, "tenant_id", app.getTenant().getId().toString());
        redisTemplate.opsForHash().put(subKey, "product_code", app.getProductCode());
        redisTemplate.opsForHash().put(subKey, "namespace", app.getNamespace());
        redisTemplate.opsForHash().put(subKey, "did_number", app.getDidNumber() != null ? app.getDidNumber() : "");
        redisTemplate.expire(subKey, 30, TimeUnit.DAYS);

        log.info("Channel limits stored for {}: max={}", app.getNamespace(), e.maxPstnChannels());
    }

    private String generateKamailioConfig(TenantApp app, PlanEntitlementResponse e) {
        String sipExternal = app.getSipExternalIp();
        String rtpengineSocket = app.getRtpengineSock();
        String authUrl = tenantServiceUrl + "/api/v1/internal/calls";

        return String.format("""
                # ================================================================
                # Kamailio Config for Tenant: %s
                # Product: %s | Plan: %s | Tier: %s
                # ================================================================
                
                alias="%s"
                alias="sip.%s.%s"
                
                listen=udp:%s:5060
                listen=tcp:%s:5060
                listen=tls:%s:5061
                listen=wss:%s:8443
                
                modparam("rtpengine", "rtpengine_sock", "%s")
                
                # ════════════════════════════════════════════════════════════════
                # HTTP Authorization - Call before routing
                # ════════════════════════════════════════════════════════════════
                modparam("http_client", "httpcon", "auth=>%s")
                
                # Usage in route[INVITE]:
                #   $var(body) = '{"didNumber":"' + $rU + '","callerNumber":"' + $fU + '"}';
                #   $var(rc) = http_connect("auth", "/authorize/inbound", "application/json", "$var(body)", "$var(result)");
                #   if ($var(rc) != 200) { sl_send_reply("403", "Denied"); exit; }
                #   $avp(call_id) = $(var(result){json.parse,callId});
                #   $avp(tenant_id) = $(var(result){json.parse,metadata.tenant_id});
                
                # Channel Limits: kamailio:channels:%s (max=%d)
                %s
                """,
                app.getNamespace(), app.getProductCode(), app.getPlanCode(), app.getPlanTier(),
                sipExternal, app.getNamespace(), baseDomain,
                sipExternal, sipExternal, sipExternal, sipExternal,
                rtpengineSocket, authUrl,
                app.getTenant().getId(), e.maxPstnChannels(),
                getProductRoutingComment(app.getProductCode()));
    }

    private String getProductRoutingComment(String productCode) {
        return switch (productCode) {
            case "AI_CC" -> "# Route: DID → Auth → AI Greeting → Queue/Self-Service";
            case "CONV_IVR" -> "# Route: DID → Auth → Conversational AI";
            case "BASIC_PBX" -> "# Route: DID → Auth → Basic IVR → Extensions";
            case "OUTBOUND_DIALER" -> "# Route: Campaign → Auth → AMD → Agent";
            case "VIRTUAL_RECEPTIONIST" -> "# Route: DID → Auth → AI Receptionist";
            default -> "# Route: Default";
        };
    }

    public void removeSubscriptionConfig(UUID subscriptionId) {
        try {
            kamailioJdbc.update("DELETE FROM subscriber WHERE rpid LIKE ?", subscriptionId + ":%");
            kamailioJdbc.update("DELETE FROM dialplan WHERE attrs LIKE ?", "%subscription_id=" + subscriptionId + "%");
            redisTemplate.delete("kamailio:subscription:" + subscriptionId);
            log.info("Removed Kamailio config for subscription {}", subscriptionId);
        } catch (Exception e) {
            log.error("Failed to remove config: {}", e.getMessage());
        }
    }
}