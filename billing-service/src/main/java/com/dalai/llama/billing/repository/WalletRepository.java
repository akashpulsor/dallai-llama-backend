package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByTenantId(UUID tenantId);

    void deleteByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);
}
