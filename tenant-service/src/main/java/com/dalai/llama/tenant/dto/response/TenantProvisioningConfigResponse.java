package com.dalai.llama.tenant.dto.response;


import java.util.UUID;

/**
 * Internal DTO used by provisioning services (K8s, Telecom, AI, PBX).
 * This DTO must NEVER be exposed to public APIs.
 */
public record TenantProvisioningConfigResponse(

        /* ========= Identity ========= */
        UUID tenantId,
        String slug,
        String namespace,

        /* ========= Deployment ========= */
        String deploymentModel,   // SHARED / DEDICATED
        String dataRegion,        // IN / EU / US

        /* ========= Messaging ========= */
        String kafkaBootstrap,
        String kafkaRegistrationTopic,
        String kafkaCallTopic,
        String kafkaAiResultTopic,
        String kafkaRtpTopic,

        /* ========= Datastores ========= */
        String redisUrl,
        String postgresUrl,
        String mysqlUrl,

        /* ========= SIP / Telecom ========= */
        String sipExternalIp,
        String sipUdpUrl,
        String sipTlsUrl,
        String turnUrl,
        String websocketUrl,
        String rtpengineSock,

        /* ========= DID / PSTN ========= */
        String didwwTrunkId,
        String didwwSipConfigId,

        /* ========= UI ========= */
        String dashboardUrl
) {}
