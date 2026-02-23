package com.dalai.llama.tenant.service.impl;



import com.dalai.llama.tenant.domain.entity.TenantApp;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import java.security.MessageDigest;
import java.util.*;

/**
 * Dedicated Namespace Provisioner
 *
 * Creates isolated Kubernetes namespace for ENTERPRISE tenants with:
 * - PostgreSQL (tenant database)
 * - Redis (caching, sessions)
 * - Kafka (events, CDR)
 * - Kamailio (SIP proxy)
 * - RTPEngine (media relay)
 * - FreeSWITCH (PBX)
 * - CoTURN (TURN server)
 * - AI Service (if AI enabled)
 * - Frontend services
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class DedicatedNamespaceProvisioner {

    private final KubernetesClient k8sClient;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.registry:dalaillama}")
    private String registry;

    @Async
    public void provisionDedicatedNamespace(TenantApp app, Runnable onComplete, Runnable onError) {
        String namespace = app.getNamespace();
        log.info("Starting dedicated namespace provisioning for: {}", namespace);

        try {
            // 1. Create namespace
            createNamespace(namespace, app);

            // 2. Create secrets
            createSecrets(namespace, app);

            // 3. Create ConfigMaps
            createConfigMaps(namespace, app);

            // 4. Deploy infrastructure
            deployPostgres(namespace);
            deployRedis(namespace);
            deployKafka(namespace);

            // 5. Deploy telecom stack
            deployKamailio(namespace, app);
            deployRTPEngine(namespace, app);
            deployFreeSWITCH(namespace, app);
            deployCoTURN(namespace, app);

            // 6. Deploy AI Service (if enabled)
            if (Boolean.TRUE.equals(app.getAiTranscriptionEnabled()) ||
                    Boolean.TRUE.equals(app.getAiBotEnabled())) {
                deployAiService(namespace, app);
            }

            // 7. Deploy frontend services
            deployFrontendServices(namespace, app);

            log.info("Dedicated namespace {} provisioned successfully", namespace);
            onComplete.run();

        } catch (Exception e) {
            log.error("Failed to provision namespace {}: {}", namespace, e.getMessage(), e);
            onError.run();
        }
    }

    private void createNamespace(String name, TenantApp app) {
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "dalaillama",
                        "dalaillama.in/tenant-id", app.getTenant().getId().toString(),
                        "dalaillama.in/subscription-id", app.getSubscriptionId().toString(),
                        "dalaillama.in/tier", "enterprise"
                ))
                .endMetadata()
                .build();
        k8sClient.namespaces().resource(ns).serverSideApply();
        log.info("Created namespace: {}", name);
    }

    private void createSecrets(String namespace, TenantApp app) {
        // Database credentials
        Secret postgresSecret = new SecretBuilder()
                .withNewMetadata()
                .withName("postgres-secret")
                .withNamespace(namespace)
                .endMetadata()
                .withStringData(Map.of(
                        "POSTGRES_USER", namespace,
                        "POSTGRES_PASSWORD", generatePassword(namespace + "-pg"),
                        "POSTGRES_DB", namespace
                ))
                .build();
        k8sClient.secrets().inNamespace(namespace).resource(postgresSecret).serverSideApply();

        // FreeSWITCH ESL
        Secret freeswitchSecret = new SecretBuilder()
                .withNewMetadata()
                .withName("freeswitch-secret")
                .withNamespace(namespace)
                .endMetadata()
                .withStringData(Map.of(
                        "ESL_PASSWORD", app.getFreeswitchEslPassword() != null ? app.getFreeswitchEslPassword() : "ClueCon"
                ))
                .build();
        k8sClient.secrets().inNamespace(namespace).resource(freeswitchSecret).serverSideApply();

        // Kamailio DB
        Secret kamailioSecret = new SecretBuilder()
                .withNewMetadata()
                .withName("kamailio-secret")
                .withNamespace(namespace)
                .endMetadata()
                .withStringData(Map.of(
                        "DB_URL", "postgresql://postgres." + namespace + ".svc.cluster.local:5432/kamailio",
                        "DB_USER", "kamailio",
                        "DB_PASSWORD", generatePassword(namespace + "-kam")
                ))
                .build();
        k8sClient.secrets().inNamespace(namespace).resource(kamailioSecret).serverSideApply();

        log.info("Created secrets in namespace: {}", namespace);
    }

    private void createConfigMaps(String namespace, TenantApp app) {
        // Kamailio config
        ConfigMap kamailioConfigMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName("kamailio-config")
                .withNamespace(namespace)
                .endMetadata()
                .withData(Map.of(
                        "kamailio.cfg", app.getKamailioConfig() != null ? app.getKamailioConfig() : "",
                        "dispatcher.list", "1 sip:freeswitch." + namespace + ".svc.cluster.local:5060 0 0 weight=100"
                ))
                .build();
        k8sClient.configMaps().inNamespace(namespace).resource(kamailioConfigMap).serverSideApply();

        // FreeSWITCH dialplan
        ConfigMap freeswitchConfigMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName("freeswitch-config")
                .withNamespace(namespace)
                .endMetadata()
                .withData(Map.of(
                        "dialplan.xml", app.getCcBuilderConfig() != null ? app.getCcBuilderConfig() : ""
                ))
                .build();
        k8sClient.configMaps().inNamespace(namespace).resource(freeswitchConfigMap).serverSideApply();

        log.info("Created configmaps in namespace: {}", namespace);
    }

    private void deployPostgres(String namespace) {
        // PVC
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName("postgres-pvc")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                .withRequests(Map.of("storage", new Quantity("20Gi")))
                .endResources()
                .endSpec()
                .build();
        k8sClient.persistentVolumeClaims().inNamespace(namespace).resource(pvc).serverSideApply();

        // Deployment
        Deployment deployment = createDeploymentWithPvc(
                "postgres", namespace, "postgres:15", 5432,
                Collections.emptyMap(), "postgres-secret", "postgres-pvc", "/var/lib/postgresql/data"
        );
        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();

        // Service
        createClusterIPService(namespace, "postgres", 5432);
        log.info("Deployed PostgreSQL in {}", namespace);
    }

    private void deployRedis(String namespace) {
        Deployment deployment = createSimpleDeployment("redis", namespace, "redis:7-alpine", 6379);
        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createClusterIPService(namespace, "redis", 6379);
        log.info("Deployed Redis in {}", namespace);
    }

    private void deployKafka(String namespace) {
        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("KAFKA_CFG_NODE_ID").withValue("0").build(),
                new EnvVarBuilder().withName("KAFKA_CFG_PROCESS_ROLES").withValue("controller,broker").build(),
                new EnvVarBuilder().withName("KAFKA_CFG_CONTROLLER_QUORUM_VOTERS").withValue("0@kafka:9093").build(),
                new EnvVarBuilder().withName("KAFKA_CFG_LISTENERS").withValue("PLAINTEXT://:9092,CONTROLLER://:9093").build(),
                new EnvVarBuilder().withName("ALLOW_PLAINTEXT_LISTENER").withValue("yes").build()
        );

        Deployment deployment = createDeploymentWithEnv("kafka", namespace, "bitnami/kafka:latest", 9092, envVars);
        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createClusterIPService(namespace, "kafka", 9092);
        log.info("Deployed Kafka in {}", namespace);
    }

    private void deployKamailio(String namespace, TenantApp app) {
        // Container ports
        List<ContainerPort> ports = List.of(
                new ContainerPortBuilder().withContainerPort(5060).withProtocol("UDP").build(),
                new ContainerPortBuilder().withContainerPort(5060).withProtocol("TCP").build(),
                new ContainerPortBuilder().withContainerPort(5061).withProtocol("TCP").build(),
                new ContainerPortBuilder().withContainerPort(8443).withProtocol("TCP").build()
        );

        // Volume mount
        VolumeMount configMount = new VolumeMountBuilder()
                .withName("config")
                .withMountPath("/etc/kamailio")
                .build();

        // Container
        Container container = new ContainerBuilder()
                .withName("kamailio")
                .withImage(registry + "/kamailio:latest")
                .withPorts(ports)
                .withEnvFrom(new EnvFromSourceBuilder()
                        .withNewSecretRef()
                        .withName("kamailio-secret")
                        .endSecretRef()
                        .build())
                .withVolumeMounts(configMount)
                .build();

        // Volume
        Volume configVolume = new VolumeBuilder()
                .withName("config")
                .withNewConfigMap()
                .withName("kamailio-config")
                .endConfigMap()
                .build();

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("kamailio")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "kamailio"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "kamailio"))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(configVolume)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();

        // Services
        createClusterIPService(namespace, "kamailio", 5060);
        createNodePortService(namespace, "kamailio-sip-udp", "kamailio", 5060, "UDP");
        createNodePortService(namespace, "kamailio-sip-tls", "kamailio", 5061, "TCP");
        createNodePortService(namespace, "kamailio-wss", "kamailio", 8443, "TCP");

        log.info("Deployed Kamailio in {}", namespace);
    }

    private void deployRTPEngine(String namespace, TenantApp app) {
        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("INTERFACES")
                        .withValue("internal/10.0.0.1;external/" + (app.getSipExternalIp() != null ? app.getSipExternalIp() : "0.0.0.0"))
                        .build(),
                new EnvVarBuilder().withName("PORT_MIN").withValue("10000").build(),
                new EnvVarBuilder().withName("PORT_MAX").withValue("60000").build()
        );

        Container container = new ContainerBuilder()
                .withName("rtpengine")
                .withImage(registry + "/rtpengine:latest")
                .withPorts(new ContainerPortBuilder().withContainerPort(22222).withProtocol("UDP").build())
                .withEnv(envVars)
                .withNewSecurityContext()
                .withPrivileged(true)
                .endSecurityContext()
                .build();

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("rtpengine")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "rtpengine"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "rtpengine"))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createClusterIPService(namespace, "rtpengine", 22222);
        log.info("Deployed RTPEngine in {}", namespace);
    }

    private void deployFreeSWITCH(String namespace, TenantApp app) {
        List<ContainerPort> ports = List.of(
                new ContainerPortBuilder().withContainerPort(5060).withProtocol("UDP").build(),
                new ContainerPortBuilder().withContainerPort(8021).withProtocol("TCP").build()
        );

        VolumeMount configMount = new VolumeMountBuilder()
                .withName("config")
                .withMountPath("/etc/freeswitch/dialplan/default")
                .build();

        Container container = new ContainerBuilder()
                .withName("freeswitch")
                .withImage(registry + "/freeswitch:latest")
                .withPorts(ports)
                .withEnvFrom(new EnvFromSourceBuilder()
                        .withNewSecretRef()
                        .withName("freeswitch-secret")
                        .endSecretRef()
                        .build())
                .withVolumeMounts(configMount)
                .build();

        Volume configVolume = new VolumeBuilder()
                .withName("config")
                .withNewConfigMap()
                .withName("freeswitch-config")
                .endConfigMap()
                .build();

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("freeswitch")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "freeswitch"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "freeswitch"))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(configVolume)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createClusterIPService(namespace, "freeswitch", 5060);
        createClusterIPService(namespace, "freeswitch-esl", 8021);
        log.info("Deployed FreeSWITCH in {}", namespace);
    }

    private void deployCoTURN(String namespace, TenantApp app) {
        List<ContainerPort> ports = List.of(
                new ContainerPortBuilder().withContainerPort(3478).withProtocol("UDP").build(),
                new ContainerPortBuilder().withContainerPort(3478).withProtocol("TCP").build(),
                new ContainerPortBuilder().withContainerPort(5349).withProtocol("TCP").build()
        );

        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("REALM").withValue(namespace + "." + baseDomain).build()
        );

        Container container = new ContainerBuilder()
                .withName("coturn")
                .withImage("coturn/coturn:latest")
                .withPorts(ports)
                .withEnv(envVars)
                .build();

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("coturn")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "coturn"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "coturn"))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createNodePortService(namespace, "coturn", "coturn", 3478, "UDP");
        log.info("Deployed CoTURN in {}", namespace);
    }

    private void deployAiService(String namespace, TenantApp app) {
        List<ContainerPort> ports = List.of(
                new ContainerPortBuilder().withContainerPort(8080).withProtocol("TCP").build(),
                new ContainerPortBuilder().withContainerPort(4573).withProtocol("TCP").build()
        );

        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("TENANT_ID").withValue(app.getTenant().getId().toString()).build(),
                new EnvVarBuilder().withName("PRODUCT").withValue(app.getProductCode()).build()
        );

        Container container = new ContainerBuilder()
                .withName("ai-service")
                .withImage(registry + "/ai-service:latest")
                .withPorts(ports)
                .withEnv(envVars)
                .build();

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("ai-service")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "ai-service"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "ai-service"))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        createClusterIPService(namespace, "ai-service", 8080);
        createClusterIPService(namespace, "ai-service-agi", 4573);
        log.info("Deployed AI Service in {}", namespace);
    }

    private void deployFrontendServices(String namespace, TenantApp app) {
        String[] frontends = {"agent-ui", "admin-ui", "supervisor-ui"};

        for (String name : frontends) {
            List<EnvVar> envVars = List.of(
                    new EnvVarBuilder().withName("KEYCLOAK_URL").withValue("https://auth." + baseDomain).build(),
                    new EnvVarBuilder().withName("KEYCLOAK_REALM").withValue(namespace).build(),
                    new EnvVarBuilder().withName("KEYCLOAK_CLIENT_ID").withValue("dalaillama-" + namespace).build()
            );

            Deployment deployment = createDeploymentWithEnv(name, namespace, registry + "/" + name + ":latest", 80, envVars);
            k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
            createClusterIPService(namespace, name, 80);
        }
        log.info("Deployed frontend services in {}", namespace);
    }

    // ================================================================
    // HELPER METHODS
    // ================================================================

    private Deployment createSimpleDeployment(String name, String namespace, String image, int port) {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withPorts(new ContainerPortBuilder().withContainerPort(port).build())
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", name))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", name))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private Deployment createDeploymentWithEnv(String name, String namespace, String image, int port, List<EnvVar> envVars) {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withPorts(new ContainerPortBuilder().withContainerPort(port).build())
                .withEnv(envVars)
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", name))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", name))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private Deployment createDeploymentWithPvc(String name, String namespace, String image, int port,
                                               Map<String, String> envMap, String secretName,
                                               String pvcName, String mountPath) {
        List<EnvVar> envVars = new ArrayList<>();
        envMap.forEach((k, v) -> envVars.add(new EnvVarBuilder().withName(k).withValue(v).build()));

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withPorts(new ContainerPortBuilder().withContainerPort(port).build())
                .withEnv(envVars);

        if (secretName != null) {
            containerBuilder.withEnvFrom(new EnvFromSourceBuilder()
                    .withNewSecretRef()
                    .withName(secretName)
                    .endSecretRef()
                    .build());
        }

        if (mountPath != null) {
            containerBuilder.withVolumeMounts(new VolumeMountBuilder()
                    .withName("data")
                    .withMountPath(mountPath)
                    .build());
        }

        Container container = containerBuilder.build();

        Volume volume = new VolumeBuilder()
                .withName("data")
                .withNewPersistentVolumeClaim()
                .withClaimName(pvcName)
                .endPersistentVolumeClaim()
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", name))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", name))
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(volume)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private void createClusterIPService(String namespace, String name, int port) {
        Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", name))
                .withPorts(new ServicePortBuilder()
                        .withPort(port)
                        .withNewTargetPort(port)
                        .build())
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(service).serverSideApply();
    }

    private void createNodePortService(String namespace, String serviceName, String appName, int port, String protocol) {
        Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(serviceName)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .withSelector(Map.of("app", appName))
                .withPorts(new ServicePortBuilder()
                        .withPort(port)
                        .withNewTargetPort(port)
                        .withProtocol(protocol)
                        .build())
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(service).serverSideApply();
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

    public void deleteDedicatedNamespace(String namespace) {
        log.info("Deleting dedicated namespace: {}", namespace);
        k8sClient.namespaces().withName(namespace).delete();
    }
}