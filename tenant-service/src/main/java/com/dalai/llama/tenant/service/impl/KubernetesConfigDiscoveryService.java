package com.dalai.llama.tenant.service.impl;


import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Kubernetes Configuration Discovery Service
 *
 * Discovers infrastructure URLs based on deployment model:
 * - SHARED: Uses platform-wide services in 'dalaillama' namespace
 * - DEDICATED: Uses tenant-specific namespace with isolated services
 */
@Slf4j
@Service
@Getter
public class KubernetesConfigDiscoveryService {

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.shared-namespace:apps}")
    private String sharedNamespace;

    // Shared Infrastructure
    @Value("${dalaillama.shared.postgres.host:postgres.dalaillama.svc.cluster.local}")
    private String sharedPostgresHost;

    @Value("${dalaillama.shared.postgres.port:5432}")
    private int sharedPostgresPort;

    @Value("${dalaillama.shared.redis.host:redis.dalaillama.svc.cluster.local}")
    private String sharedRedisHost;

    @Value("${dalaillama.shared.redis.port:6379}")
    private int sharedRedisPort;

    @Value("${dalaillama.shared.kafka.bootstrap:kafka.dalaillama.svc.cluster.local:9092}")
    private String sharedKafkaBootstrap;

    // Shared SIP/Telephony
    @Value("${dalaillama.shared.sip.host:sip.dalaillama.in}")
    private String sharedSipHost;

    @Value("${dalaillama.shared.turn.host:turn.dalaillama.in}")
    private String sharedTurnHost;

    @Value("${dalaillama.shared.rtpengine.socket:udp:rtpengine.dalaillama.svc.cluster.local:22222}")
    private String sharedRtpengineSocket;

    @Value("${dalaillama.shared.freeswitch.host:freeswitch.dalaillama.svc.cluster.local}")
    private String sharedFreeswitchHost;

    @Value("${dalaillama.shared.freeswitch.esl.port:8021}")
    private int sharedFreeswitchEslPort;

    @Value("${dalaillama.shared.freeswitch.esl.password:ClueCon}")
    private String sharedFreeswitchEslPassword;

    // AI Service
    @Value("${dalaillama.shared.ai.host:ai-service.dalaillama.svc.cluster.local}")
    private String sharedAiServiceHost;

    @Value("${dalaillama.shared.ai.port:8080}")
    private int sharedAiServicePort;

    @Value("${dalaillama.shared.ai.agi.port:4573}")
    private int sharedAiAgiPort;

    public String getSharedPostgresUrl() {
        return String.format("jdbc:postgresql://%s:%d/dalaillama", sharedPostgresHost, sharedPostgresPort);
    }

    public String getSharedRedisUrl() {
        return String.format("redis://%s:%d", sharedRedisHost, sharedRedisPort);
    }

    public DedicatedEndpoints buildDedicatedEndpoints(String tenantSlug) {
        String namespace = tenantSlug;
        return DedicatedEndpoints.builder()
                .postgresUrl(String.format("jdbc:postgresql://postgres.%s.svc.cluster.local:5432/%s", namespace, tenantSlug))
                .redisUrl(String.format("redis://redis.%s.svc.cluster.local:6379", namespace))
                .kafkaBootstrap(String.format("kafka.%s.svc.cluster.local:9092", namespace))
                .sipExternalIp("sip." + tenantSlug + "." + baseDomain)
                .sipInternalHost(String.format("kamailio.%s.svc.cluster.local", namespace))
                .turnHost("turn." + tenantSlug + "." + baseDomain)
                .websocketUrl("wss://ws." + tenantSlug + "." + baseDomain + "/ws")
                .rtpengineSocket(String.format("udp:rtpengine.%s.svc.cluster.local:22222", namespace))
                .freeswitchHost(String.format("freeswitch.%s.svc.cluster.local", namespace))
                .freeswitchEslPort(8021)
                .freeswitchEslPassword(generatePassword(tenantSlug + "-esl"))
                .aiServiceHost(String.format("ai-service.%s.svc.cluster.local", namespace))
                .aiServicePort(8080)
                .aiAgiHost(String.format("ai-service.%s.svc.cluster.local", namespace))
                .aiAgiPort(4573)
                .build();
    }

    public String buildAppUrl(String subdomain, String tenantSlug) {
        return String.format("https://%s.%s.%s", subdomain, tenantSlug, baseDomain);
    }

    public String buildKeycloakClientId(String tenantSlug, String appSuffix) {
        String base = "dalaillama-" + tenantSlug;
        return (appSuffix == null || appSuffix.isBlank()) ? base : base + "-" + appSuffix;
    }

    public String buildKeycloakIssuerUrl(String tenantSlug) {
        return String.format("https://auth.%s/realms/%s", baseDomain, tenantSlug);
    }

    public String getAiAgiUrl(boolean dedicated, String tenantSlug) {
        if (dedicated) {
            return String.format("agi://ai-service.%s.svc.cluster.local:%d", tenantSlug, sharedAiAgiPort);
        }
        return String.format("agi://%s:%d", sharedAiServiceHost, sharedAiAgiPort);
    }

    public String getAiServiceUrl(boolean dedicated, String tenantSlug) {
        if (dedicated) {
            return String.format("http://ai-service.%s.svc.cluster.local:%d", tenantSlug, sharedAiServicePort);
        }
        return String.format("http://%s:%d", sharedAiServiceHost, sharedAiServicePort);
    }

    private String generatePassword(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 24);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
    }

    public String getDomain(){
        return this.baseDomain;
    }

    @Builder
    public record DedicatedEndpoints(
            String postgresUrl, String redisUrl, String kafkaBootstrap,
            String sipExternalIp, String sipInternalHost, String turnHost,
            String websocketUrl, String rtpengineSocket,
            String freeswitchHost, int freeswitchEslPort, String freeswitchEslPassword,
            String aiServiceHost, int aiServicePort, String aiAgiHost, int aiAgiPort
    ) {}
}