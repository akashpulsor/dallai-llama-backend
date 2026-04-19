package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<Transaction> findBySubscriptionId(UUID subscriptionId);

    Optional<Transaction> findByReference(String reference);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Transaction> findByTenantIdAndType(UUID tenantId, TransactionType type);

    List<Transaction> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID tenantId, Instant from, Instant to);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.tenantId = :tenantId AND t.type = :type AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByTenantIdAndTypeAndPeriod(UUID tenantId, TransactionType type, Instant from, Instant to);
}
