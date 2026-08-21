package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.VoiceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VoiceProfileRepository extends JpaRepository<VoiceProfile, UUID> {

    Optional<VoiceProfile> findByTenantIdAndProjectIdAndCharacterRefAndLanguage(
            UUID tenantId, UUID projectId, String characterRef, String language);
}
