package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentDataRepository  extends JpaRepository<PaymentData, Integer> {


    Optional<PaymentData> findByCallId(int callId);
}
