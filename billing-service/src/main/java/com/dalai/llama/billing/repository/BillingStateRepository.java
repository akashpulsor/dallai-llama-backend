package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.BillingState;
import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingStateRepository extends JpaRepository<BillingState, UUID> {

    Optional<BillingState> findByTenantId(UUID tenantId);

    List<BillingState> findByStateAndGraceExpiresAtBefore(
            BillingStateType state,
            Instant time
    );
}
