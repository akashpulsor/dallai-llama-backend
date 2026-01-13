package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.SipProvider;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SipTrunkRepository extends JpaRepository<SipTrunk, UUID> {

    List<SipTrunk> findByTenantId(UUID tenantId);

    List<SipTrunk> findByProvider(SipProvider provider);

    List<SipTrunk> findByStatus(SipTrunkStatus status);

    Optional<SipTrunk> findByDidwwTrunkId(String didwwTrunkId);
}
