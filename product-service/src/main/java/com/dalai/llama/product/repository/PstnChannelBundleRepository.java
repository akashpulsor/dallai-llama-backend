package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.PstnChannelBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PstnChannelBundleRepository extends JpaRepository<PstnChannelBundle, UUID> {

    List<PstnChannelBundle> findByTenantId(UUID tenantId);

    Optional<PstnChannelBundle> findByTenantIdAndSipTrunk_Id(UUID tenantId, UUID sipTrunkId);
}
