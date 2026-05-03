package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DidRepository extends JpaRepository<Did, UUID> {

    @Query("SELECT d.number FROM Did d WHERE d.status <> com.dalai.llama.product.domain.entity.enums.DidStatus.RELEASED")
    Set<String> findAllReservedNumbers();

    List<Did> findByTenantId(UUID tenantId);

    Optional<Did> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Did> findByTenantIdAndNumber(UUID tenantId, String number);

    long countByTenantIdAndStatusIn(UUID tenantId, List<DidStatus> statuses);

    List<Did> findByStatus(DidStatus status);


    List<Did> findByTenantIdAndStatus(UUID tenantId, DidStatus status);

    Optional<Did> findByNumber(String number);

    boolean existsByNumber(String number);
}
