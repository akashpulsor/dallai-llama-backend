package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
