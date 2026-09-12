package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.VoiceIdentityType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CastProfileRepository extends JpaRepository<CastProfile, UUID> {

    Optional<CastProfile> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Library entries ({@code project_id IS NULL}) plus anything scoped to this project. */
    List<CastProfile> findByTenantIdAndProjectIdIsNullOrTenantIdAndProjectId(
            UUID tenantIdForLibrary, UUID tenantIdForProject, UUID projectId);

    /** Atomic claim: only the first completed clone may populate a previously empty profile. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CastProfile profile
            set profile.clonedVoiceId = :clonedVoiceId,
                profile.clonedVoiceProviderId = :providerId,
                profile.voiceIdentityType = :voiceIdentityType,
                profile.updatedAt = :updatedAt
            where profile.id = :castProfileId
              and profile.tenantId = :tenantId
              and profile.clonedVoiceId is null
              and profile.clonedVoiceProviderId is null
              and profile.builtinVoiceId is null
            """)
    int persistClonedVoiceIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("castProfileId") UUID castProfileId,
            @Param("clonedVoiceId") String clonedVoiceId,
            @Param("providerId") String providerId,
            @Param("voiceIdentityType") VoiceIdentityType voiceIdentityType,
            @Param("updatedAt") java.time.OffsetDateTime updatedAt);
}
