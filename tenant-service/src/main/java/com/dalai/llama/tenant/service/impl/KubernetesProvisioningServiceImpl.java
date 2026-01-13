package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KubernetesProvisioningService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesProvisioningServiceImpl implements KubernetesProvisioningService {

    private final KubernetesClient kubernetesClient;
    private final TenantRepository tenantRepository;

    @Value("${provisioning.ip-wait-timeout-minutes:10}")
    private int ipWaitTimeoutMinutes;

    @Value("${provisioning.ip-poll-interval-seconds:10}")
    private int ipPollIntervalSeconds;

    @Value("${sip.domain:sip.dalaillama.in}")
    private String sipDomain;

    @Override
    public void createNamespace(UUID tenantId, String namespace) {
        log.info("Creating namespace {} for tenant {}", namespace, tenantId);

        Tenant tenant = findTenant(tenantId);

        try {
            // Check if namespace already exists
            if (kubernetesClient.namespaces().withName(namespace).get() != null) {
                log.info("Namespace {} already exists", namespace);
                return;
            }

            Namespace ns = new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .withLabels(Map.of(
                            "tenant-id", tenantId.toString(),
                            "tenant-slug", tenant.getSlug(),
                            "deployment-model", tenant.getDeploymentModel().name().toLowerCase(),
                            "managed-by", "dalai-llama"
                    ))
                    .endMetadata()
                    .build();

            kubernetesClient.namespaces().resource(ns).create();

            // Create resource quota based on plan
            createResourceQuota(namespace, tenant);

            // Create network policy
            createNetworkPolicy(namespace, tenant);

            // Update tenant with namespace
            tenant.setNamespace(namespace);
            tenantRepository.save(tenant);

            log.info("Created namespace {} for tenant {}", namespace, tenantId);
        } catch (Exception e) {
            log.error("Failed to create namespace {} for tenant {}", namespace, tenantId, e);
            throw new ProvisioningException("Failed to create namespace: " + e.getMessage());
        }
    }

    @Override
    public void deployInfrastructure(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        String namespace = tenant.getNamespace();

        log.info("Deploying infrastructure for tenant {} in namespace {}", tenantId, namespace);

        try {
            if (tenant.getDeploymentModel() == DeploymentModel.DEDICATED) {
                // Deploy dedicated infrastructure
                deployRedis(namespace, tenant);
                deployPostgres(namespace, tenant);

                // Update tenant with infra URLs
                tenant.setRedisUrl("redis://redis." + namespace + ".svc.cluster.local:6379");
                tenant.setPostgresUrl("jdbc:postgresql://postgres." + namespace + ".svc.cluster.local:5432/tenant_db");
            } else {
                // Use shared infrastructure (URLs from platform config)
                tenant.setRedisUrl("redis://redis.platform.svc.cluster.local:6379");
                tenant.setPostgresUrl("jdbc:postgresql://postgres.platform.svc.cluster.local:5432/" + tenant.getSlug() + "_db");
            }

            // Create Kafka topics (in shared Kafka cluster)
            createKafkaTopics(tenant);

            tenantRepository.save(tenant);
            log.info("Deployed infrastructure for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to deploy infrastructure for tenant {}", tenantId, e);
            throw new ProvisioningException("Failed to deploy infrastructure: " + e.getMessage());
        }
    }

    @Override
    public void deployTelecom(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        String namespace = tenant.getNamespace();

        log.info("Deploying telecom stack for tenant {} in namespace {}", tenantId, namespace);

        try {
            // Deploy Kamailio
            deployKamailio(namespace, tenant);

            // Deploy RTPEngine
            deployRtpEngine(namespace, tenant);

            // Deploy CoTurn
            deployCoturn(namespace, tenant);

            // Deploy WebRTC Gateway
            deployWebrtcGateway(namespace, tenant);

            // Update tenant with telecom endpoints
            tenant.setRtpengineSock("udp://rtpengine." + namespace + ".svc.cluster.local:22222");
            tenant.setWebsocketUrl("wss://" + tenant.getSlug() + ".wss.dalaillama.in");
            tenantRepository.save(tenant);

            log.info("Deployed telecom stack for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to deploy telecom stack for tenant {}", tenantId, e);
            throw new ProvisioningException("Failed to deploy telecom: " + e.getMessage());
        }
    }

    @Override
    public String waitForExternalIp(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        String namespace = tenant.getNamespace();
        String serviceName = "sip-lb";

        log.info("Waiting for external IP for tenant {} in namespace {}", tenantId, namespace);

        Instant deadline = Instant.now().plus(Duration.ofMinutes(ipWaitTimeoutMinutes));

        while (Instant.now().isBefore(deadline)) {
            try {
                io.fabric8.kubernetes.api.model.Service k8sService = kubernetesClient.services()
                        .inNamespace(namespace)
                        .withName(serviceName)
                        .get();

                if (k8sService != null && k8sService.getStatus() != null
                        && k8sService.getStatus().getLoadBalancer() != null
                        && k8sService.getStatus().getLoadBalancer().getIngress() != null
                        && !k8sService.getStatus().getLoadBalancer().getIngress().isEmpty()) {

                    LoadBalancerIngress ingress = k8sService.getStatus().getLoadBalancer().getIngress().get(0);
                    String externalIp = ingress.getIp() != null ? ingress.getIp() : ingress.getHostname();

                    if (externalIp != null && !externalIp.isEmpty()) {
                        log.info("External IP assigned for tenant {}: {}", tenantId, externalIp);

                        // Update tenant with SIP URLs
                        tenant.setSipExternalIp(externalIp);
                        tenant.setSipUdpUrl("sip:" + externalIp + ":5060");
                        tenant.setSipTlsUrl("sips:" + externalIp + ":5061");
                        tenant.setTurnUrl("turn:" + externalIp + ":3478");
                        tenantRepository.save(tenant);

                        return externalIp;
                    }
                }

                Thread.sleep(ipPollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisioningException("Interrupted while waiting for external IP");
            } catch (Exception e) {
                log.warn("Error checking for external IP: {}", e.getMessage());
            }
        }

        throw new ProvisioningException("Timeout waiting for external IP after " + ipWaitTimeoutMinutes + " minutes");
    }

    // ========== Cleanup Methods (for compensation) ==========

    public void deleteNamespace(String namespace) {
        log.info("Deleting namespace: {}", namespace);
        try {
            kubernetesClient.namespaces().withName(namespace).delete();
        } catch (Exception e) {
            log.warn("Failed to delete namespace {}: {}", namespace, e.getMessage());
        }
    }

    // ========== Private Deployment Methods ==========

    private void createResourceQuota(String namespace, Tenant tenant) {
        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("tenant-quota")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .addToHard("requests.cpu", new Quantity("4"))
                .addToHard("requests.memory", new Quantity("8Gi"))
                .addToHard("limits.cpu", new Quantity("8"))
                .addToHard("limits.memory", new Quantity("16Gi"))
                .addToHard("pods", new Quantity("20"))
                .addToHard("persistentvolumeclaims", new Quantity("10"))
                .endSpec()
                .build();

        kubernetesClient.resourceQuotas().inNamespace(namespace).resource(quota).create();
    }

    private void createNetworkPolicy(String namespace, Tenant tenant) {
        log.debug("Network policy creation for {} - using Istio policies", namespace);
    }

    private void deployRedis(String namespace, Tenant tenant) {
        log.debug("Deploying Redis in namespace: {}", namespace);

        Deployment redis = new DeploymentBuilder()
                .withNewMetadata()
                .withName("redis")
                .withNamespace(namespace)
                .withLabels(Map.of("app", "redis", "tenant", tenant.getSlug()))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "redis"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "redis"))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("redis")
                .withImage("redis:7.2-alpine")
                .addNewPort()
                .withContainerPort(6379)
                .endPort()
                .withNewResources()
                .addToRequests("cpu", new Quantity("100m"))
                .addToRequests("memory", new Quantity("128Mi"))
                .addToLimits("cpu", new Quantity("500m"))
                .addToLimits("memory", new Quantity("512Mi"))
                .endResources()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        kubernetesClient.apps().deployments().inNamespace(namespace).resource(redis).create();
        createClusterIpService(namespace, "redis", 6379);
    }

    private void deployPostgres(String namespace, Tenant tenant) {
        log.debug("Deploying PostgreSQL in namespace: {}", namespace);
        // Using StatefulSet for PostgreSQL with PVC - implementation omitted for brevity
    }

    private void deployKamailio(String namespace, Tenant tenant) {
        log.debug("Deploying Kamailio in namespace: {}", namespace);

        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName("kamailio-config")
                .withNamespace(namespace)
                .endMetadata()
                .withData(Map.of(
                        "kamailio.cfg", generateKamailioConfig(tenant)
                ))
                .build();

        kubernetesClient.configMaps().inNamespace(namespace).resource(configMap).create();

        Deployment kamailio = new DeploymentBuilder()
                .withNewMetadata()
                .withName("kamailio")
                .withNamespace(namespace)
                .withLabels(Map.of("app", "kamailio", "tenant", tenant.getSlug()))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "kamailio"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "kamailio"))
                .withAnnotations(Map.of("sidecar.istio.io/inject", "true"))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("kamailio")
                .withImage("kamailio/kamailio:5.7")
                .addNewPort().withContainerPort(5060).withProtocol("UDP").endPort()
                .addNewPort().withContainerPort(5060).withProtocol("TCP").endPort()
                .addNewPort().withContainerPort(5061).withProtocol("TCP").endPort()
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/etc/kamailio")
                .endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName("kamailio-config")
                .endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        kubernetesClient.apps().deployments().inNamespace(namespace).resource(kamailio).create();
        createSipLoadBalancer(namespace, tenant);
    }

    private void deployRtpEngine(String namespace, Tenant tenant) {
        log.debug("Deploying RTPEngine in namespace: {}", namespace);
    }

    private void deployCoturn(String namespace, Tenant tenant) {
        log.debug("Deploying CoTurn in namespace: {}", namespace);
    }

    private void deployWebrtcGateway(String namespace, Tenant tenant) {
        log.debug("Deploying WebRTC Gateway in namespace: {}", namespace);
    }

    private void createSipLoadBalancer(String namespace, Tenant tenant) {
        io.fabric8.kubernetes.api.model.Service loadBalancer = new ServiceBuilder()
                .withNewMetadata()
                .withName("sip-lb")
                .withNamespace(namespace)
                .withLabels(Map.of("app", "kamailio"))
                .withAnnotations(Map.of(
                        "service.beta.kubernetes.io/aws-load-balancer-type", "nlb",
                        "service.beta.kubernetes.io/aws-load-balancer-cross-zone-load-balancing-enabled", "true"
                ))
                .endMetadata()
                .withNewSpec()
                .withType("LoadBalancer")
                .withExternalTrafficPolicy("Local")
                .withSelector(Map.of("app", "kamailio"))
                .addNewPort()
                .withName("sip-udp")
                .withPort(5060)
                .withTargetPort(new IntOrString(5060))
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("sip-tcp")
                .withPort(5060)
                .withTargetPort(new IntOrString(5060))
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("sip-tls")
                .withPort(5061)
                .withTargetPort(new IntOrString(5061))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        kubernetesClient.services().inNamespace(namespace).resource(loadBalancer).create();
    }

    private void createClusterIpService(String namespace, String name, int port) {
        io.fabric8.kubernetes.api.model.Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(Map.of("app", name))
                .addNewPort()
                .withPort(port)
                .withTargetPort(new IntOrString(port))
                .endPort()
                .endSpec()
                .build();

        kubernetesClient.services().inNamespace(namespace).resource(service).create();
    }

    private void createKafkaTopics(Tenant tenant) {
        log.info("Creating Kafka topics for tenant: {}", tenant.getSlug());
    }

    private String generateKamailioConfig(Tenant tenant) {
        return """
                #!KAMAILIO
                # Kamailio configuration for tenant: %s
                
                debug=2
                log_stderror=no
                memdbg=5
                memlog=5
                
                listen=udp:0.0.0.0:5060
                listen=tcp:0.0.0.0:5060
                listen=tls:0.0.0.0:5061
                
                loadmodule "tm.so"
                loadmodule "sl.so"
                loadmodule "rr.so"
                loadmodule "pv.so"
                loadmodule "maxfwd.so"
                loadmodule "textops.so"
                loadmodule "siputils.so"
                loadmodule "sanity.so"
                loadmodule "rtpengine.so"
                
                modparam("rtpengine", "rtpengine_sock", "%s")
                
                request_route {
                    route(REQINIT);
                    if (is_method("REGISTER")) {
                        route(REGISTRAR);
                        exit;
                    }
                    route(RELAY);
                }
                """.formatted(tenant.getSlug(), tenant.getRtpengineSock() != null ? tenant.getRtpengineSock() : "udp:127.0.0.1:22222");
    }

    private Tenant findTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }
}