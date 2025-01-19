package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.ConversationData;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationDataRepository  extends JpaRepository<ConversationData, Integer> {
}
