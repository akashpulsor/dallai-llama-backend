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
}
