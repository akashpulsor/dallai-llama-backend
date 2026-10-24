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
    /** Addresses minted before readable ones: cr_ + the tenant UUID's 32 hex chars. */
    private static final String LEGACY_PREFIX = "cr_";
    /** Never a creator's: the platform's own aliases on the outreach domain and role names. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("brands", "outreach", "noreply", "no.reply", "admin",
            "support", "help", "hello", "info", "team", "ops", "billing", "postmaster", "abuse", "security", "dalai", "dalaillama",
            "dalai.llama", "creator", "creators");

    private final CreatorEmailIdentityRepository repository;
    private final LeadManagementProperties properties;

    @Override
    @Transactional
    public CreatorEmailIdentity provisionForCreator(UUID tenantId, String displayName) {
        // Picking a free address and inserting it must not interleave with another provision.
        repository.lockAddresses();
        Optional<CreatorEmailIdentity> existing = repository.findByTenantId(tenantId);
        if (existing.isPresent()) {
            CreatorEmailIdentity row = existing.get();
            // Rename-safe: local_part and email are held stable across renames. Only the
            // display-name and updated_at move. Skipping the save when nothing changed
            // avoids the write and the updated_at churn.
            boolean changed = false;
            if (displayName != null && !displayName.equals(row.getDisplayName())) {
                row.setDisplayName(displayName);
                changed = true;
            }
            // A cr_<uuid> address reads as machine mail; it gets a readable one, once.
            if (row.getLocalPart().startsWith(LEGACY_PREFIX)) {
                String localPart = freeLocalPart(displayName != null ? displayName : row.getDisplayName());
                log.info("Creator email identity tenantId={} renamed {} -> {}", tenantId, row.getLocalPart(), localPart);
                row.setLocalPart(localPart);
                row.setEmail(localPart + "@" + properties.domain());
                changed = true;
            }
            if (changed) repository.save(row);
            return row;
        }

        String localPart = freeLocalPart(displayName);
        CreatorEmailIdentity minted = CreatorEmailIdentity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .localPart(localPart)
                .email(localPart + "@" + properties.domain())
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

    /** The first free of {@code asha.rao}, {@code asha.rao2}, {@code asha.rao3}... on the domain. */
    private String freeLocalPart(String displayName) {
        String base = readableLocalPart(displayName);
        String candidate = base;
        for (int n = 2; RESERVED.contains(candidate)
                || repository.findByEmailIgnoreCase(candidate + "@" + properties.domain()).isPresent(); n++) {
            candidate = base + n;
        }
        return candidate;
    }

    /** "Asha Rao's Studio" -> {@code asha.raos.studio}: lower-case ASCII letters and digits,
     * words joined by dots, at most 30 chars; {@code creator} when nothing is left. */
    static String readableLocalPart(String displayName) {
        String ascii = java.text.Normalizer.normalize(displayName == null ? "" : displayName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replace("'", "");
        String joined = String.join(".", ascii.split("[^a-z0-9]+")).replaceAll("^\\.+|\\.+$", "");
        if (joined.length() > 30) joined = joined.substring(0, 30).replaceAll("\\.+$", "");
        return joined.isEmpty() ? "creator" : joined;
    }

    private static String generatePassword() {
        char[] out = new char[PASSWORD_LENGTH];
        for (int i = 0; i < out.length; i++) {
            out[i] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(out);
    }
}
