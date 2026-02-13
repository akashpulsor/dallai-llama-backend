package com.dalai.llama.tenant.service.provisioning.telecom.k8s;

import com.dalai.llama.tenant.domain.entity.Tenant;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes deployer for telecom stack components.
 *
 * Deploys:
 * - Kamailio (SBC)
 * - FreeSWITCH (Media Server)
 * - RTPEngine (Media Proxy)
 * - CoTURN (STUN/TURN)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelecomK8sDeployer {

    private final KubernetesClient k8sClient;

    @Value("${telecom.images.kamailio:dalaillama/kamailio:5.7}")
    private String kamailioImage;

    @Value("${telecom.images.freeswitch:dalaillama/freeswitch:1.10}")
    private String freeswitchImage;

    @Value("${telecom.images.rtpengine:dalaillama/rtpengine:latest}")
    private String rtpengineImage;

    @Value("${telecom.images.coturn:dalaillama/coturn:latest}")
    private String coturnImage;

    /**
     * Create all ConfigMaps for telecom stack
     */
    public void createConfigMaps(String namespace, String tenantSlug,
                                 Map<String, String> kamailioFiles,
                                 Map<String, String> freeswitchFiles,
                                 Map<String, String> rtpengineFiles,
                                 Map<String, String> coturnFiles) {

        log.info("Creating ConfigMaps for telecom stack in namespace {}", namespace);

        createConfigMap(namespace, "kamailio-config", kamailioFiles, tenantSlug);
        createConfigMap(namespace, "freeswitch-config", freeswitchFiles, tenantSlug);
        createConfigMap(namespace, "rtpengine-config", rtpengineFiles, tenantSlug);
        createConfigMap(namespace, "coturn-config", coturnFiles, tenantSlug);
    }

    private void createConfigMap(String namespace, String name, Map<String, String> data, String tenantSlug) {
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "dalai-llama",
                        "tenant", tenantSlug
                ))
                .endMetadata()
                .withData(data)
                .build();

        k8sClient.configMaps().inNamespace(namespace).resource(cm).createOrReplace();
        log.info("Created ConfigMap {}/{}", namespace, name);
    }

    /**
     * Update existing ConfigMap
     */
    public void updateConfigMap(String namespace, String name, Map<String, String> data) {
        ConfigMap existing = k8sClient.configMaps().inNamespace(namespace).withName(name).get();
        if (existing != null) {
            existing.getData().putAll(data);
            k8sClient.configMaps().inNamespace(namespace).resource(existing).update();
            log.info("Updated ConfigMap {}/{}", namespace, name);
        }
    }

    /**
     * Deploy RTPEngine (first, as others depend on it)
     */
    public void deployRtpEngine(String namespace, Tenant tenant) {
        log.info("Deploying RTPEngine for tenant {} in namespace {}", tenant.getSlug(), namespace);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("rtpengine")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "rtpengine",
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "rtpengine"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of(
                        "app", "rtpengine",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "sidecar.istio.io/inject", "false"  // RTPEngine needs direct network
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("rtpengine")
                .withImage(rtpengineImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("ng")
                .withContainerPort(22222)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("rtp-start")
                .withContainerPort(10000)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("rtp-end")
                .withContainerPort(20000)
                .withProtocol("UDP")
                .endPort()
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/etc/rtpengine")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("recordings")
                .withMountPath("/var/lib/recordings")
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("100m"))
                .addToRequests("memory", new Quantity("256Mi"))
                .addToLimits("cpu", new Quantity("1000m"))
                .addToLimits("memory", new Quantity("1Gi"))
                .endResources()
                .withNewSecurityContext()
                .withPrivileged(true)  // Required for kernel module (optional)
                .endSecurityContext()
                .endContainer()
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName("rtpengine-config")
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("recordings")
                .withNewEmptyDir()
                .endEmptyDir()
                .endVolume()
                .withHostNetwork(true)  // For RTP port range
                .withDnsPolicy("ClusterFirstWithHostNet")
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();

        // Create service
        createClusterIpService(namespace, "rtpengine", 22222, "UDP");
    }

    /**
     * Deploy CoTURN
     */
    public void deployCoturn(String namespace, Tenant tenant) {
        log.info("Deploying CoTURN for tenant {} in namespace {}", tenant.getSlug(), namespace);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("coturn")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "coturn",
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "coturn"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of(
                        "app", "coturn",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "sidecar.istio.io/inject", "false"
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("coturn")
                .withImage(coturnImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("stun-udp")
                .withContainerPort(3478)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("stun-tcp")
                .withContainerPort(3478)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("turn-tls")
                .withContainerPort(5349)
                .withProtocol("TCP")
                .endPort()
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/etc/coturn")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("certs")
                .withMountPath("/etc/coturn/certs")
                .withReadOnly(true)
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("50m"))
                .addToRequests("memory", new Quantity("128Mi"))
                .addToLimits("cpu", new Quantity("500m"))
                .addToLimits("memory", new Quantity("512Mi"))
                .endResources()
                .endContainer()
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName("coturn-config")
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("certs")
                .withNewSecret()
                .withSecretName("turn-tls-certs")
                .withOptional(true)
                .endSecret()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
    }

    /**
     * Deploy FreeSWITCH
     */
    public void deployFreeSWITCH(String namespace, Tenant tenant) {
        log.info("Deploying FreeSWITCH for tenant {} in namespace {}", tenant.getSlug(), namespace);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("freeswitch")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "freeswitch",
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "freeswitch"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of(
                        "app", "freeswitch",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "sidecar.istio.io/inject", "true"
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("freeswitch")
                .withImage(freeswitchImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("sip")
                .withContainerPort(5060)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("sip-tcp")
                .withContainerPort(5060)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("sip-ext")
                .withContainerPort(5080)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("esl")
                .withContainerPort(8021)
                .withProtocol("TCP")
                .endPort()
                .addNewVolumeMount()
                .withName("dialplan")
                .withMountPath("/etc/freeswitch/dialplan/default")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("directory")
                .withMountPath("/etc/freeswitch/directory/default")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("sofia")
                .withMountPath("/etc/freeswitch/sip_profiles")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("recordings")
                .withMountPath("/var/lib/freeswitch/recordings")
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("200m"))
                .addToRequests("memory", new Quantity("512Mi"))
                .addToLimits("cpu", new Quantity("2000m"))
                .addToLimits("memory", new Quantity("2Gi"))
                .endResources()
                .withNewLivenessProbe()
                .withNewTcpSocket()
                .withNewPort(8021)
                .endTcpSocket()
                .withInitialDelaySeconds(30)
                .withPeriodSeconds(10)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewTcpSocket()
                .withNewPort(8021)
                .endTcpSocket()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(5)
                .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                .withName("dialplan")
                .withNewConfigMap()
                .withName("freeswitch-config")
                .addNewItem()
                .withKey("dialplan.xml")
                .withPath("tenant.xml")
                .endItem()
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("directory")
                .withNewConfigMap()
                .withName("freeswitch-config")
                .addNewItem()
                .withKey("directory.xml")
                .withPath("tenant.xml")
                .endItem()
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("sofia")
                .withNewConfigMap()
                .withName("freeswitch-config")
                .addNewItem()
                .withKey("sofia.conf.xml")
                .withPath("sofia.conf.xml")
                .endItem()
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("recordings")
                .withNewPersistentVolumeClaim()
                .withClaimName("freeswitch-recordings-pvc")
                .endPersistentVolumeClaim()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();

        // Create services
        createClusterIpService(namespace, "freeswitch", 5060, "UDP");
        createClusterIpService(namespace, "freeswitch-tcp", 5060, "TCP");
        createClusterIpService(namespace, "freeswitch-ext", 5080, "UDP");
        createClusterIpService(namespace, "freeswitch-esl", 8021, "TCP");

        // Create PVC for recordings
        createRecordingsPvc(namespace, tenant.getSlug());
    }

    /**
     * Deploy Kamailio
     */
    public void deployKamailio(String namespace, Tenant tenant) {
        log.info("Deploying Kamailio for tenant {} in namespace {}", tenant.getSlug(), namespace);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("kamailio")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "kamailio",
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "kamailio"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of(
                        "app", "kamailio",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "sidecar.istio.io/inject", "true"
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("kamailio")
                .withImage(kamailioImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("sip-udp")
                .withContainerPort(5060)
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("sip-tcp")
                .withContainerPort(5060)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("sip-tls")
                .withContainerPort(5061)
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("wss")
                .withContainerPort(8089)
                .withProtocol("TCP")
                .endPort()
                .addNewEnv()
                .withName("EXTERNAL_IP")
                .withNewValueFrom()
                .withNewFieldRef()
                .withFieldPath("status.hostIP")
                .endFieldRef()
                .endValueFrom()
                .endEnv()
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/etc/kamailio")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("tls")
                .withMountPath("/etc/kamailio/tls")
                .withReadOnly(true)
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("100m"))
                .addToRequests("memory", new Quantity("256Mi"))
                .addToLimits("cpu", new Quantity("1000m"))
                .addToLimits("memory", new Quantity("1Gi"))
                .endResources()
                .withNewLivenessProbe()
                .withNewExec()
                .withCommand("kamctl", "rpc", "core.echo")
                .endExec()
                .withInitialDelaySeconds(30)
                .withPeriodSeconds(10)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewTcpSocket()
                .withNewPort(5060)
                .endTcpSocket()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(5)
                .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName("kamailio-config")
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("tls")
                .withNewSecret()
                .withSecretName("kamailio-tls-certs")
                .withOptional(true)
                .endSecret()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
    }

    /**
     * Create all services including LoadBalancer
     */
    public void createServices(String namespace, Tenant tenant) {
        log.info("Creating services for telecom stack in namespace {}", namespace);

        // Internal ClusterIP services
        createClusterIpService(namespace, "kamailio", 5060, "UDP");
        createClusterIpService(namespace, "kamailio-tcp", 5060, "TCP");

        // LoadBalancer for external SIP access
        createSipLoadBalancer(namespace, tenant);

        // LoadBalancer for TURN
        createTurnLoadBalancer(namespace, tenant);
    }

    private void createClusterIpService(String namespace, String name, int port, String protocol) {
        String appLabel = name.split("-")[0];  // Extract base app name

        Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(Map.of("app", appLabel))
                .addNewPort()
                .withPort(port)
                .withTargetPort(new IntOrString(port))
                .withProtocol(protocol)
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
    }

    private void createSipLoadBalancer(String namespace, Tenant tenant) {
        Service lb = new ServiceBuilder()
                .withNewMetadata()
                .withName("kamailio-sip-lb")
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
                .addNewPort()
                .withName("wss")
                .withPort(8089)
                .withTargetPort(new IntOrString(8089))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(lb).createOrReplace();
    }

    private void createTurnLoadBalancer(String namespace, Tenant tenant) {
        Service lb = new ServiceBuilder()
                .withNewMetadata()
                .withName("coturn-lb")
                .withNamespace(namespace)
                .withLabels(Map.of("app", "coturn"))
                .withAnnotations(Map.of(
                        "service.beta.kubernetes.io/aws-load-balancer-type", "nlb"
                ))
                .endMetadata()
                .withNewSpec()
                .withType("LoadBalancer")
                .withExternalTrafficPolicy("Local")
                .withSelector(Map.of("app", "coturn"))
                .addNewPort()
                .withName("stun-udp")
                .withPort(3478)
                .withTargetPort(new IntOrString(3478))
                .withProtocol("UDP")
                .endPort()
                .addNewPort()
                .withName("stun-tcp")
                .withPort(3478)
                .withTargetPort(new IntOrString(3478))
                .withProtocol("TCP")
                .endPort()
                .addNewPort()
                .withName("turn-tls")
                .withPort(5349)
                .withTargetPort(new IntOrString(5349))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(lb).createOrReplace();
    }

    private void createRecordingsPvc(String namespace, String tenantSlug) {
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName("freeswitch-recordings-pvc")
                .withNamespace(namespace)
                .withLabels(Map.of("tenant", tenantSlug))
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                .addToRequests("storage", new Quantity("50Gi"))
                .endResources()
                .withStorageClassName("standard")
                .endSpec()
                .build();

        k8sClient.persistentVolumeClaims().inNamespace(namespace).resource(pvc).createOrReplace();
    }
}
