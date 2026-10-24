package com.dalai.llama.tenant.leadmanagement.repository;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorEmailIdentityRepository extends JpaRepository<CreatorEmailIdentity, UUID> {

    Optional<CreatorEmailIdentity> findByTenantId(UUID tenantId);

    /** Used by the inbound-email router to resolve an incoming
     * {@code cr_<...>@partner.dalaillama.in} back to the owning creator. Lookup key -- kept as
     * a case-insensitive match because Cloudflare Email Routing has been observed to
     * lower-case the recipient before delivering the event. */
    Optional<CreatorEmailIdentity> findByEmailIgnoreCase(String email);

    /** Holds the address-allocation lock until the transaction ends, so two creators with the
     * same name provisioned at once get {@code asha@} and {@code asha2@}, not a UNIQUE(email)
     * failure. */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true,
            value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('creator_email_identity.email'))) l")
    Integer lockAddresses();
}
