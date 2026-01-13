package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.CompliancePolicy;
import com.dalai.llama.tenant.domain.entity.RecordingPolicy;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.dto.mapper.TenantMapper;
import com.dalai.llama.tenant.dto.request.UpdateCompliancePolicyRequest;
import com.dalai.llama.tenant.dto.request.UpdateRecordingPolicyRequest;
import com.dalai.llama.tenant.dto.response.CompliancePolicyResponse;
import com.dalai.llama.tenant.dto.response.RecordingPolicyResponse;
import com.dalai.llama.tenant.repository.CompliancePolicyRepository;
import com.dalai.llama.tenant.repository.RecordingPolicyRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.ComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ComplianceServiceImpl implements ComplianceService {

    private final TenantRepository tenantRepository;
    private final CompliancePolicyRepository compliancePolicyRepository;
    private final RecordingPolicyRepository recordingPolicyRepository;
    private final TenantMapper tenantMapper;

    @Override
    @Transactional(readOnly = true)
    public CompliancePolicyResponse getCompliancePolicy(UUID tenantId) {
        assertTenantExists(tenantId);
        CompliancePolicy policy = compliancePolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaultCompliancePolicy(tenantId));
        return tenantMapper.toResponse(policy);
    }

    @Override
    public CompliancePolicyResponse updateCompliancePolicy(UUID tenantId, UpdateCompliancePolicyRequest request) {
        assertTenantExists(tenantId);

        CompliancePolicy policy = compliancePolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaultCompliancePolicy(tenantId));

        tenantMapper.updateComplianceFromRequest(request, policy);
        policy = compliancePolicyRepository.save(policy);

        log.info("Updated compliance policy for tenant: {}", tenantId);
        return tenantMapper.toResponse(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public RecordingPolicyResponse getRecordingPolicy(UUID tenantId) {
        assertTenantExists(tenantId);
        RecordingPolicy policy = recordingPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaultRecordingPolicy(tenantId));
        return tenantMapper.toResponse(policy);
    }

    @Override
    public RecordingPolicyResponse updateRecordingPolicy(UUID tenantId, UpdateRecordingPolicyRequest request) {
        assertTenantExists(tenantId);

        RecordingPolicy policy = recordingPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaultRecordingPolicy(tenantId));

        tenantMapper.updateRecordingFromRequest(request, policy);
        policy = recordingPolicyRepository.save(policy);

        log.info("Updated recording policy for tenant: {}", tenantId);
        return tenantMapper.toResponse(policy);
    }

    // ========== Private Helpers ==========

    private void assertTenantExists(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new TenantNotFoundException(tenantId);
        }
    }

    private CompliancePolicy createDefaultCompliancePolicy(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        CompliancePolicy policy = new CompliancePolicy();
        policy.setTenant(tenant);
        // Defaults are set in entity
        return compliancePolicyRepository.save(policy);
    }

    private RecordingPolicy createDefaultRecordingPolicy(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        RecordingPolicy policy = new RecordingPolicy();
        policy.setTenant(tenant);
        // Defaults are set in entity
        return recordingPolicyRepository.save(policy);
    }
}