package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.boot.crm.exceptions.PaymentDataNotFoundException;
import org.springframework.boot.crm.repository.PaymentDataRepository;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentDataRepository paymentDataRepository;

    public PaymentService(PaymentDataRepository paymentDataRepository) {
        this.paymentDataRepository = paymentDataRepository;
    }


    public PaymentData addPaymentData(PaymentData paymentData) {
        return this.paymentDataRepository.save(paymentData);
    }

    public PaymentData getPaymentDataByCallId(int callId) {
        return this.paymentDataRepository.findByCallId(callId).orElseThrow(()
        -> new PaymentDataNotFoundException("Payment data not found"));
    }
}
