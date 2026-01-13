package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.repository.SignalingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;


@Service
@RequiredArgsConstructor
public class SignalingConfigServiceImpl implements SignalingConfigService{

    private final SignalingConfigRepository repo;

    @Override
    public SignalingConfig getConfigForTenant(String tenantId) {

        return repo.findByTenantId(tenantId)
                .orElseThrow(() -> new NoSuchElementException("SignalingConfig not found for tenant: " + tenantId));
    }

    @Override
    public SignalingConfig save(SignalingConfig config) {
        config.setUpdatedAt(Instant.now());
        return repo.save(config);
    }
}
