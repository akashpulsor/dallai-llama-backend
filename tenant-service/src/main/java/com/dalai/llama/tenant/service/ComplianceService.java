package com.dalai.llama.tenant.service;


import com.dalai.llama.tenant.dto.request.UpdateCompliancePolicyRequest;
import com.dalai.llama.tenant.dto.request.UpdateRecordingPolicyRequest;
import com.dalai.llama.tenant.dto.response.CompliancePolicyResponse;
import com.dalai.llama.tenant.dto.response.RecordingPolicyResponse;

import java.util.UUID;

public interface ComplianceService {

    CompliancePolicyResponse getCompliancePolicy(UUID tenantId);

    CompliancePolicyResponse updateCompliancePolicy(
            UUID tenantId,
            UpdateCompliancePolicyRequest request
    );

    RecordingPolicyResponse getRecordingPolicy(UUID tenantId);

    RecordingPolicyResponse updateRecordingPolicy(
            UUID tenantId,
            UpdateRecordingPolicyRequest request
    );
}
