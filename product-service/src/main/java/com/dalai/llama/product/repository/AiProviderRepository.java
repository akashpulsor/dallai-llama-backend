package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.AiProvider;
import com.dalai.llama.product.domain.entity.enums.AiProviderType;
import com.dalai.llama.product.domain.entity.enums.AiStackType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiProviderRepository extends JpaRepository<AiProvider, UUID> {

    List<AiProvider> findByActiveTrue();

    List<AiProvider> findByQualityTierAndActiveTrue(AiStackType tier);

    List<AiProvider> findByProviderTypeAndActiveTrue(AiProviderType type);

    List<AiProvider> findByProviderTypeAndQualityTierAndActiveTrue(AiProviderType type, AiStackType tier);

    Optional<AiProvider> findByProviderTypeAndProviderNameAndModelName(
            AiProviderType type, String providerName, String modelName);

    @Query("SELECT a FROM AiProvider a WHERE a.qualityTier = :tier AND a.providerType = :type " +
            "AND a.active = true ORDER BY a.costPerMin ASC")
    List<AiProvider> findCheapestByTierAndType(AiStackType tier, AiProviderType type);
}
