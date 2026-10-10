package com.dalai.llama.tenant.leadmanagement.inquiry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BrandInquiryRepository extends JpaRepository<BrandInquiry, UUID> {

    List<BrandInquiry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<BrandInquiry> findByBrandContactIdOrderByCreatedAtDesc(UUID brandContactId);

    long countByTenantIdAndStatus(UUID tenantId, BrandInquiry.Status status);
}
