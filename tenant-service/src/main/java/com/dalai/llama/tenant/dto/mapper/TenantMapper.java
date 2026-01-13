package com.dalai.llama.tenant.dto.mapper;

import com.dalai.llama.tenant.domain.entity.CompliancePolicy;
import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.RecordingPolicy;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateCompliancePolicyRequest;
import com.dalai.llama.tenant.dto.request.UpdateRecordingPolicyRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import org.mapstruct.*;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TenantMapper {

    /* ========= Tenant ========= */

    Tenant toEntity(CreateTenantRequest request);

    TenantResponse toResponse(Tenant tenant);

    TenantDetailResponse toDetailResponse(Tenant tenant);

    void updateTenantFromRequest(
            UpdateTenantRequest request,
            @MappingTarget Tenant tenant
    );

    /* ========= Compliance ========= */

    CompliancePolicyResponse toResponse(CompliancePolicy policy);

    void updateComplianceFromRequest(
            UpdateCompliancePolicyRequest request,
            @MappingTarget CompliancePolicy policy
    );

    /* ========= Recording ========= */

    RecordingPolicyResponse toResponse(RecordingPolicy policy);

    void updateRecordingFromRequest(
            UpdateRecordingPolicyRequest request,
            @MappingTarget RecordingPolicy policy
    );

    /* ========= Provisioning ========= */

    ProvisioningStatusResponse toProvisioningStatus(
            ProvisioningTask task
    );
}
