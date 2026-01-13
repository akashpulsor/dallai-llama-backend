package com.dalai.llama.tenant.domain.entity.enums;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProvisioningStep {

    CREATE_KEYCLOAK_REALM(1, "Create Keycloak Realm", true),
    CREATE_KEYCLOAK_ROLES(2, "Create Keycloak Roles", true),
    CREATE_KEYCLOAK_CLIENT(3, "Create OAuth2 Client", true),
    CREATE_KEYCLOAK_ADMIN(4, "Create Admin User", true),
    CREATE_NAMESPACE(5, "Create Kubernetes Namespace", true),
    DEPLOY_INFRA_KAFKA(6, "Deploy Kafka (Dedicated)", true),
    DEPLOY_INFRA_REDIS(7, "Deploy Redis (Dedicated)", true),
    DEPLOY_INFRA_POSTGRES(8, "Deploy PostgreSQL (Dedicated)", true),
    DEPLOY_INFRA_MYSQL(9, "Deploy MySQL (Dedicated)", true),
    CREATE_KAFKA_TOPICS(10, "Create Kafka Topics", true),
    DEPLOY_KAMAILIO(11, "Deploy Kamailio SIP Proxy", true),
    DEPLOY_RTPENGINE(12, "Deploy RTPEngine Media Proxy", true),
    DEPLOY_COTURN(13, "Deploy CoTurn TURN Server", true),
    DEPLOY_WEBRTC_GW(14, "Deploy WebRTC Gateway", true),
    DEPLOY_ASTERISK(15, "Deploy Asterisk PBX", true),
    DEPLOY_AI_SERVICE(16, "Deploy AI Service", true),
    DEPLOY_AGENT_SERVICE(17, "Deploy Agent Service", true),
    DEPLOY_CALL_CONTROL(18, "Deploy Call Control Service", true),
    CREATE_SIP_LOADBALANCER(19, "Create SIP LoadBalancer", true),
    CREATE_WEBRTC_INGRESS(20, "Create WebRTC Ingress", true),
    WAIT_EXTERNAL_IP(21, "Wait for External IP", false),
    CONFIGURE_DIDWW_TRUNK(22, "Configure DIDWW SIP Trunk", true),
    CONFIGURE_DIDWW_DIDS(23, "Point DIDs to External IP", true),
    DEPLOY_CC_DASHBOARD(24, "Deploy Contact Center Dashboard", true),
    CREATE_TENANT_USERS(25, "Create Initial Users", true),
    HEALTH_CHECK(26, "Health Check All Components", false),
    FINALIZE(27, "Finalize Provisioning", false);

    private final int order;
    private final String description;
    private final boolean compensatable;
}
