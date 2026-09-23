package com.dalai.llama.tenant.leadmanagement.service.impl;

import com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties;
import com.dalai.llama.tenant.leadmanagement.domain.CreatorEmailIdentityStatus;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.repository.CreatorEmailIdentityRepository;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorEmailIdentityServiceImpl implements CreatorEmailIdentityService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 24-char URL-safe alphabet: no 0/O/1/l ambiguity when the human operator reads the
     * password off an admin screen and types it into a mail client's account setup. Length
     * 24 gives ~139 bits of entropy over this alphabet -- comfortably above any brute-force
     * threat for an internal-hosted SMTP/IMAP mailbox. */
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int PASSWORD_LENGTH = 24;

    private final CreatorEmailIdentityRepository repository;
    private final LeadManagementProperties properties;

    @Override
    @Transactional
    public CreatorEmailIdentity provisionForCreator(UUID tenantId, String displayName) {
        String email = computeEmail(tenantId);
        Optional<CreatorEmailIdentity> existing = repository.findByTenantId(tenantId);
        if (existing.isPresent()) {
            CreatorEmailIdentity row = existing.get();
            // Rename-safe: local_part and email are held stable across renames. Only the
            // display-name and updated_at move. Skipping the save when displayName is
            // unchanged avoids the write and the updated_at churn.
            if (displayName != null && !displayName.equals(row.getDisplayName())) {
                row.setDisplayName(displayName);
                repository.save(row);
            }
            return row;
        }

        CreatorEmailIdentity minted = CreatorEmailIdentity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .localPart(localPart(tenantId))
                .email(email)
                .displayName(displayName)
                .emailPassword(generatePassword())
                .status(CreatorEmailIdentityStatus.PROVISIONED)
                .build();
        try {
            CreatorEmailIdentity saved = repository.save(minted);
            log.info("Provisioned creator email identity tenantId={} email={}",
                    tenantId, saved.getEmail());
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Concurrent activation (two consumer threads on the same partition, or a
            // manual re-drive alongside the natural delivery) can race past the initial
            // findByTenantId. The UNIQUE (tenant_id) constraint catches it; re-read and
            // return the row the other thread wrote.
            log.warn("Race on creator email identity for tenantId={} -- re-reading", tenantId, race);
            return repository.findByTenantId(tenantId)
                    .orElseThrow(() -> race);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreatorEmailIdentity> findByTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreatorEmailIdentity> findByEmail(String email) {
        return repository.findByEmailIgnoreCase(email);
    }

    @Override
    @Transactional
    public CreatorEmailIdentity rotatePassword(UUID tenantId) {
        CreatorEmailIdentity row = repository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No creator email identity for tenant " + tenantId + " -- provision first"));
        row.setEmailPassword(generatePassword());
        CreatorEmailIdentity saved = repository.save(row);
        log.info("Rotated creator email password tenantId={} email={}", tenantId, saved.getEmail());
        return saved;
    }

    @Override
    public String computeEmail(UUID tenantId) {
        return localPart(tenantId) + "@" + properties.domain();
    }

    /** cr_ + the 32 lower-case hex chars of the UUID, hyphens stripped. Standard Java
     * {@code UUID.toString()} already emits lower-case, but the explicit
     * {@code toLowerCase(Locale.ROOT)} guards against a future JVM change and makes the
     * intent explicit. */
    private static String localPart(UUID tenantId) {
        return "cr_" + tenantId.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String generatePassword() {
        char[] out = new char[PASSWORD_LENGTH];
        for (int i = 0; i < out.length; i++) {
            out[i] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(out);
    }
}
