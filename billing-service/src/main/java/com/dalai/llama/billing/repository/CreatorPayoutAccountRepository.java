package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreatorPayoutAccountRepository extends JpaRepository<CreatorPayoutAccount, UUID> {
}
