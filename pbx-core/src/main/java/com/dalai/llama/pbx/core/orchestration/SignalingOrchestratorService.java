package com.dalai.llama.pbx.core.orchestration;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Tenant;
import com.dalai.llama.pbx.core.util.TemplateRenderer;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalingOrchestratorService {

    private final KubernetesClient k8s;
    private final TemplateRenderer renderer;

    // ---------------- SHARED PLATFORM INFRA ----------------

    @Value("${platform.kafka.bootstrap:}")
    private String platformKafkaBootstrap;

    @Value("${platform.redis.url:}")
    private String platformRedisUrl;

    @Value("${platform.postgres.url:}")
    private String platformPostgresUrl;

    @Value("${platform.mysql.url:}")
    private String platformMysqlUrl;
    @Value("${pbx.core.url}")
    private String pbxCoreInternalUrl;

    // ==========================================================
    //  SECURE PASSWORD GENERATION → deterministic + epoch
    // ==========================================================

    private String genPass(String seed, long epoch) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((seed + epoch).getBytes());
            byte[] digest = sha.digest();

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==========================================================
    //    MAIN ORCHESTRATION (RETURNS UPDATED TENANT!)
    // ==========================================================

    public Tenant launchStackWithConfig(Tenant tenant, SignalingConfig cfg, String clientName, String kamCfg) {

        long epoch = System.currentTimeMillis();

        String model = tenant.getDeploymentModel();
        boolean isDedicated = "dedicated".equalsIgnoreCase(model);

        String ns = isDedicated ? "tenant-" + tenant.getId() : "pbx-shared";
        String infraPrefix = isDedicated ? ("tenant-" + tenant.getId()) : "shared";

        // --------------------------------------------
        // 1. Create namespace (ONLY for dedicated)
        // --------------------------------------------
        if (isDedicated) {
            k8s.namespaces().createOrReplace(
                    new NamespaceBuilder()
                            .withNewMetadata().withName(ns).endMetadata()
                            .build()
            );
        }

        // --------------------------------------------
        // 2. Generate passwords if missing
        // --------------------------------------------
        if (tenant.getKafkaPassword() == null) {
            tenant.setKafkaUsername("tenant_" + tenant.getId());
            tenant.setKafkaPassword(genPass(tenant.getId(), epoch));
        }
        if (tenant.getRedisPassword() == null) {
            tenant.setRedisUsername("tenant_" + tenant.getId());
            tenant.setRedisPassword(genPass(tenant.getId() + "_redis", epoch));
        }
        if (tenant.getPostgresPassword() == null) {
            tenant.setPostgresUsername("tenant_" + tenant.getId());
            tenant.setPostgresPassword(genPass(tenant.getId() + "_pg", epoch));
        }
        if (tenant.getMysqlPassword() == null) {
            tenant.setMysqlUsername("tenant_" + tenant.getId());
            tenant.setMysqlPassword(genPass(tenant.getId() + "_mysql", epoch));
        }

        // --------------------------------------------
        // 3. Deterministic topic suffix
        // --------------------------------------------

        UUID uuid = generateUuidFromInputs(
                model,
                tenant.getId(),
                tenant.getName(),
                tenant.getRealm(),
                ""
        );

        String suffix = uuid.toString().replace("-", "").substring(0, 8);

        String base = "pbx-" + model + "-" + tenant.getId() + "-" + suffix;

        String regTopic = "registration-events-" + base;
        String callTopic = "call-events-" + base;
        String aiTopic   = "ai-results-" + base;
        String rtpTopic  = "rtp-events-" + base;

        tenant.setKafkaRegTopic(regTopic);
        tenant.setKafkaCallTopic(callTopic);
        tenant.setKafkaAiResultTopic(aiTopic);
        tenant.setKafkaRtpTopic(rtpTopic);

        // --------------------------------------------
        // 4. Resolve shared infra URLs
        // --------------------------------------------

        String kafkaShared = resolve("SHARED_KAFKA", platformKafkaBootstrap,
                "kafka.shared.svc.cluster.local:9092");
        String redisShared = resolve("SHARED_REDIS", platformRedisUrl,
                "redis.shared.svc.cluster.local:6379");
        String postgresShared = resolve("SHARED_POSTGRES", platformPostgresUrl,
                "postgres.shared.svc.cluster.local:5432");
        String mysqlShared = resolve("SHARED_MYSQL", platformMysqlUrl,
                "mysql.shared.svc.cluster.local:3306");


        String kafkaUrl;
        String redisUrl;
        String pgUrl;
        String mysqlUrl;

        // =============================================
        // 5. SHARED INFRA MODE
        // =============================================
        if (!isDedicated) {

            kafkaUrl = kafkaShared;
            redisUrl = redisShared;
            pgUrl = postgresShared;
            mysqlUrl = mysqlShared;

            tenant.setKafkaBootstrap(kafkaUrl);
            tenant.setRedisUrl(redisUrl);
            tenant.setPostgresUrl(pgUrl);
            tenant.setMysqlUrl(mysqlUrl);

            createKafkaTopic(kafkaUrl, regTopic);
            createKafkaTopic(kafkaUrl, callTopic);
            createKafkaTopic(kafkaUrl, aiTopic);
            createKafkaTopic(kafkaUrl, rtpTopic);
        }

        // =============================================
        // 6. DEDICATED INFRA MODE
        // =============================================
        else {

            apply("k8s/templates/kafka.yaml", Map.of(), ns);
            kafkaUrl = "kafka-" + infraPrefix + "." + ns + ".svc.cluster.local:9092";

            apply("k8s/templates/redis.yaml", Map.of(), ns);
            redisUrl = "redis-" + infraPrefix + "." + ns + ".svc.cluster.local:6379";

            apply("k8s/templates/postgres.yaml", Map.of(), ns);
            pgUrl = "postgres-" + infraPrefix + "." + ns + ".svc.cluster.local:5432";

            apply("k8s/templates/mysql.yaml", Map.of(), ns);
            mysqlUrl = "mysql-" + infraPrefix + "." + ns + ".svc.cluster.local:3306";

            // if pod fails → ERASE URLs
            try {
                waitPod(ns, "kafka-" + infraPrefix);
                waitPod(ns, "redis-" + infraPrefix);
                waitPod(ns, "postgres-" + infraPrefix);
                waitPod(ns, "mysql-" + infraPrefix);
            } catch (Exception failed) {
                log.error("❌ Dedicated infra failed for tenant {}", tenant.getId());
                tenant.setKafkaBootstrap(null);
                tenant.setRedisUrl(null);
                tenant.setPostgresUrl(null);
                tenant.setMysqlUrl(null);
                return tenant;
            }

            tenant.setKafkaBootstrap(kafkaUrl);
            tenant.setRedisUrl(redisUrl);
            tenant.setPostgresUrl(pgUrl);
            tenant.setMysqlUrl(mysqlUrl);

            createKafkaTopic(kafkaUrl, regTopic);
            createKafkaTopic(kafkaUrl, callTopic);
            createKafkaTopic(kafkaUrl, aiTopic);
            createKafkaTopic(kafkaUrl, rtpTopic);
        }

        // =============================================
        // 7. Compute dynamic service URLs for tenant
        // =============================================

        String kamailioSipUdp   = "sip:" + "kamailio-" + tenant.getId() + "." + ns + ".svc.cluster.local:5060";
        String kamailioSipTls   = "sips:" + "kamailio-" + tenant.getId() + "." + ns + ".svc.cluster.local:5061";
        String webrtcWsUrl      = cfg.getWssUrl();
        String rtpengineSock    = "udp:rtpengine-" + tenant.getId() + ":22222";

        tenant.setSipUdpUrl(kamailioSipUdp);
        tenant.setSipTlsUrl(kamailioSipTls);
        tenant.setWebsocketUrl(webrtcWsUrl);
        tenant.setRtpengineSock(rtpengineSock);

        // =============================================
        // 8. Build Vars for Templates
        // =============================================
        String pbxCoreUrl = pbxCoreInternalUrl;

        tenant.setPbxCoreUrlInternal(pbxCoreUrl);  // Optional, only if you want to expose it to admin


        Map<String, String> vars = new HashMap<>();

        vars.put("TENANT_ID", tenant.getId());
        vars.put("REALM", tenant.getRealm());
        vars.put("NAMESPACE", ns);

        vars.put("KAFKA_BROKERS", kafkaUrl);
        vars.put("REDIS_URL", redisUrl);
        vars.put("POSTGRES_URL", pgUrl);
        vars.put("MYSQL_URL", mysqlUrl);

        vars.put("RTPENGINE_SOCK", rtpengineSock);

        vars.put("PBX_CORE_URL", pbxCoreUrl);
        vars.put("WSS_URL", webrtcWsUrl);

        vars.put("KAFKA_REG_TOPIC", regTopic);
        vars.put("KAFKA_CALL_TOPIC", callTopic);
        vars.put("AI_RESULT_TOPIC", aiTopic);
        vars.put("RTP_TOPIC", rtpTopic);

        // =============================================
        // 9. KAMAILIO CONFIGMAP
        // =============================================

        var cm = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
                .withNewMetadata().withName("kamailio-config-" + tenant.getId()).endMetadata()
                .addToData("kamailio.cfg", kamCfg)
                .build();

        k8s.configMaps().inNamespace(ns).createOrReplace(cm);

        // Deploy kamailio
        apply("k8s/templates/kamailio.yaml", vars, ns);
        apply("k8s/templates/coturn.yaml", vars, ns);
        apply("k8s/templates/webrtc-gw.yaml", vars, ns);
        apply("k8s/templates/rtpengine.yaml", vars, ns);

        apply("k8s/templates/agent-service.yaml", vars, ns);
        apply("k8s/templates/ai-service.yaml", vars, ns);
        try {
            waitPod(ns, "kamailio");
            waitPod(ns, "coturn");
            waitPod(ns, "webrtc-gw");
            waitPod(ns, "rtpengine");
            waitPod(ns, "ai-service");
            waitPod(ns, "agent-service");
        } catch (Exception fail) {
            log.error("❌ PBX stack failed for tenant {}", tenant.getId());
            tenant.setStatus("error");
            return tenant;
        }

        tenant.setStatus("active");

        // =============================================
        // 10. RETURN UPDATED TENANT
        // =============================================

        return tenant;
    }

    // ---------------- internal helpers ----------------

    private void apply(String classpathFile, Map<String, String> vars, String ns) {
        String yaml = renderer.renderTemplateFile(classpathFile, vars);
        k8s.load(new ByteArrayInputStream(yaml.getBytes())).inNamespace(ns).createOrReplace();
    }

    private void waitPod(String ns, String appLabel) {
        k8s.pods()
                .inNamespace(ns)
                .withLabel("app", appLabel)
                .waitUntilCondition(
                        p -> p.getStatus() != null &&
                                "Running".equals(p.getStatus().getPhase()),
                        5, TimeUnit.MINUTES
                );
    }

    private void createKafkaTopic(String brokers, String topic) {
        try (var admin =
                     org.apache.kafka.clients.admin.AdminClient.create(
                             Map.of("bootstrap.servers", brokers))) {

            var existing = admin.listTopics().names().get();
            if (!existing.contains(topic)) {
                admin.createTopics(List.of(
                        new org.apache.kafka.clients.admin.NewTopic(topic, 3, (short) 1)
                )).all().get();
            }

        } catch (Exception ex) {
            log.error("Failed to create Kafka topic {}", topic, ex);
        }
    }

    private String resolve(String envKey, String propValue, String fallback) {
        if (propValue != null && !propValue.isBlank()) return propValue;
        String env = System.getenv(envKey);
        return env != null && !env.isBlank() ? env : fallback;
    }

    public static UUID generateUuidFromInputs(String deploymentModel, String input1, String input2, String input3, String input4) {
        String composite = Stream.of(deploymentModel, input1, input2, input3, input4)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.joining("|"));

        return UUID.nameUUIDFromBytes(composite.getBytes());
    }

    /**
     * Ensures that the tenant stack inside a namespace is healthy.
     *
     * - If the namespace is "pbx-shared", health = true (shared infra is always running)
     * - For dedicated namespaces we verify that:
     *      → All deployments exist
     *      → All replicas are READY
     *
     * - If unhealthy → we clear tenant infra URLs and set status = "error"
     * - If healthy   → we set status = "active"
     *
     * @return true if healthy, false otherwise
     */
    public boolean ensureHealth(Tenant tenant) {

        boolean isDedicated = "dedicated".equalsIgnoreCase(tenant.getDeploymentModel());

        String ns = isDedicated ? "tenant-" + tenant.getId() : "pbx-shared";

        // Shared infra does not deploy infra pods → always healthy
        if ("pbx-shared".equalsIgnoreCase(ns)) {
            log.info("SKIP health check – shared infra uses global Kafka/Redis/Postgres/MySQL.");
            tenant.setStatus("active");
            return true;
        }

        try {

            var deployments = k8s.apps().deployments()
                    .inNamespace(ns)
                    .list()
                    .getItems();

            if (deployments == null || deployments.isEmpty()) {
                log.error("❌ No deployments found in namespace {}", ns);
                markTenantAsError(tenant, "no deployments");
                return false;
            }

            for (var dep : deployments) {

                String name = dep.getMetadata().getName();

                int desired = Optional.ofNullable(dep.getSpec())
                        .map(s -> Optional.ofNullable(s.getReplicas()).orElse(0))
                        .orElse(0);

                int ready = Optional.ofNullable(dep.getStatus())
                        .map(s -> Optional.ofNullable(s.getReadyReplicas()).orElse(0))
                        .orElse(0);

                if (ready < desired) {
                    log.error("❌ Deployment {} not healthy: ready={} desired={}", name, ready, desired);
                    markTenantAsError(tenant, "deployment not ready: " + name);
                    return false;
                }
            }

            // All good
            tenant.setStatus("active");
            log.info("✅ Namespace {} is healthy", ns);
            return true;

        } catch (Exception ex) {
            log.error("❌ Health check failed on namespace {}: {}", ns, ex.getMessage());
            markTenantAsError(tenant, "exception: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Clears tenant infra URLs & marks status as error.
     * This prevents agents from trying to connect to broken or partially failed infra.
     */
    private void markTenantAsError(Tenant tenant, String reason) {
        tenant.setStatus("error");

        tenant.setKafkaBootstrap(null);
        tenant.setRedisUrl(null);
        tenant.setPostgresUrl(null);
        tenant.setMysqlUrl(null);

        tenant.setSipUdpUrl(null);
        tenant.setSipTlsUrl(null);
        tenant.setWebsocketUrl(null);
        tenant.setRtpengineSock(null);

        log.warn("⚠ Tenant {} marked as ERROR: {}", tenant.getId(), reason);
    }

}
