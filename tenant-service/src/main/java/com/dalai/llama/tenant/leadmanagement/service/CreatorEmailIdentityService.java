package com.dalai.llama.tenant.leadmanagement.service;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;

import java.util.Optional;
import java.util.UUID;

public interface CreatorEmailIdentityService {

    /** Idempotent: called from the {@code subscription.activated} Kafka handler, so a
     * re-delivered event MUST NOT create a duplicate row or a second identity for the same
     * creator. Returns the identity that exists (whether just created or already there);
     * {@code displayName} is refreshed on the existing row but the {@code email} field is
     * held stable across renames -- that is the entire point of using the immutable tenant
     * UUID as the local-part source. */
    CreatorEmailIdentity provisionForCreator(UUID tenantId, String displayName);

    Optional<CreatorEmailIdentity> findByTenant(UUID tenantId);

    Optional<CreatorEmailIdentity> findByEmail(String email);

    /** Pure function: same input, same output, no I/O. Public so callers (e.g. the inbound
     * router) can compute the expected address without hitting the DB, and so tests can pin
     * the format. Format: {@code cr_<32-hex-chars-no-dashes>@<configured-domain>}. */
    String computeEmail(UUID tenantId);
}
