package com.dalai.llama.tenant.leadmanagement.service;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;

import java.util.Optional;
import java.util.UUID;

public interface CreatorEmailIdentityService {

    /** Idempotent: called from the {@code subscription.activated} Kafka handler, so a
     * re-delivered event MUST NOT create a duplicate row or a second identity for the same
     * creator. Returns the identity that exists (whether just created or already there);
     * {@code displayName} is refreshed on the existing row but the {@code email} field is
     * held stable across renames. The address is readable, from the creator's name
     * ({@code asha.rao@}); a legacy {@code cr_<uuid>@} address is replaced by one once. */
    CreatorEmailIdentity provisionForCreator(UUID tenantId, String displayName);

    Optional<CreatorEmailIdentity> findByTenant(UUID tenantId);

    Optional<CreatorEmailIdentity> findByEmail(String email);

    /** Rotates the mailbox password on an existing identity. Returns the row with the new
     * plaintext password. Throws {@link IllegalArgumentException} if no identity exists for
     * the tenant (caller should provision first). */
    CreatorEmailIdentity rotatePassword(UUID tenantId);
}
