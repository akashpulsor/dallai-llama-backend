package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByTenantId(UUID tenantId);

    void deleteByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);


    /**
     * Locks the wallet row for the duration of the transaction.
     * Use for atomic balance checks + debits/credits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.tenantId = :tenantId")
    Optional<Wallet> findByTenantIdForUpdate(@Param("tenantId") UUID tenantId);
}
