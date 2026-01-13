package com.dalai.llama.tenant.service;


import java.util.UUID;

public interface KubernetesProvisioningService {

    void createNamespace(UUID tenantId, String namespace);

    void deployInfrastructure(UUID tenantId);

    void deployTelecom(UUID tenantId);

    String waitForExternalIp(UUID tenantId);
}
