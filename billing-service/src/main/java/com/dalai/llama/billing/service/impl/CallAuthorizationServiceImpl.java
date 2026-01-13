package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import com.dalai.llama.billing.repository.BillingStateRepository;
import com.dalai.llama.billing.service.CallAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CallAuthorizationServiceImpl implements CallAuthorizationService {

    private final BillingStateRepository billingStateRepository;

    @Override
    public boolean authorizeCall(UUID tenantId) {
        return billingStateRepository.findByTenantId(tenantId)
                .map(state -> state.getState() == BillingStateType.ACTIVE
                        || state.getState() == BillingStateType.GRACE)
                .orElse(false);
    }
}
