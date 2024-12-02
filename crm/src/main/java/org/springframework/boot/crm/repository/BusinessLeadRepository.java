package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.AgentData;
import org.springframework.boot.crm.entity.BusinessLead;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BusinessLeadRepository  extends JpaRepository<BusinessLead, Integer> {

    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId")
    List<LeadData> findLeadsByBusinessId(@Param("businessId") int businessId);

    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId")
    Page<LeadData> findLeadsByBusinessId(@Param("businessId") int businessId, Pageable pageable);

    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId and ld.leadId =:leadId")
    Optional<LeadData> findByLeadIdAndBusinessId(int leadId, int businessId);

}
