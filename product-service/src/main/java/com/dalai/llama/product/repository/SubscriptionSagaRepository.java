package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.SubscriptionSaga;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionSagaRepository extends JpaRepository<SubscriptionSaga, UUID> {

    Optional<SubscriptionSaga> findBySubscriptionId(UUID subscriptionId);

    boolean existsByTriggeredByEventId(UUID eventId);
}