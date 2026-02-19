package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.TenantSipTrunk;
import com.dalai.llama.product.repository.TenantSipTrunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantSipTrunkService {

    private final TenantSipTrunkRepository repository;

    @Value("${sip.domain:sip.dalaillama.in}")
    private String sipDomain;

    @Value("${sip.realm:dalaillama.in}")
    private String sipRealm;

    @Value("${sip.port:5060}")
    private int sipPort;

    @Transactional
    public TenantSipTrunk createForSubscription(UUID tenantId, UUID subscriptionId, String tenantSlug) {
        return repository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    String username = "tenant_" + tenantSlug.replace("-", "_") + "_trunk";
                    String password = generatePassword(12);
                    String ha1 = computeHA1(username, sipRealm, password);

                    TenantSipTrunk trunk = TenantSipTrunk.builder()
                            .tenantId(tenantId)
                            .subscriptionId(subscriptionId)
                            .username(username)
                            .passwordHash(ha1)
                            .passwordPlain(password)
                            .realm(sipRealm)
                            .domain(sipDomain)
                            .port(sipPort)
                            .maxConcurrentCalls(10)
                            .active(true)
                            .build();

                    trunk = repository.save(trunk);
                    log.info("Created SIP trunk for tenant {}: {}", tenantId, username);
                    return trunk;
                });
    }

    public TenantSipTrunk getByTenantId(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElse(null);
    }

    @Transactional
    public TenantSipTrunk regeneratePassword(UUID tenantId) {
        TenantSipTrunk trunk = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new RuntimeException("Trunk not found"));

        String password = generatePassword(12);
        trunk.setPasswordHash(computeHA1(trunk.getUsername(), trunk.getRealm(), password));
        trunk.setPasswordPlain(password);
        trunk.setSyncedToKamailio(false);

        repository.save(trunk);
        log.info("Regenerated SIP password for tenant {}", tenantId);
        return trunk;
    }

    private String generatePassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String computeHA1(String username, String realm, String password) {
        try {
            String input = username + ":" + realm + ":" + password;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HA1", e);
        }
    }
}