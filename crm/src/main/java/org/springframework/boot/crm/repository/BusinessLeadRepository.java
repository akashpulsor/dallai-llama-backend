package org.springframework.boot.crm.repository;

import jakarta.persistence.QueryHint;
import org.springframework.boot.crm.entity.AgentData;
import org.springframework.boot.crm.entity.BusinessLead;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;

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
            "WHERE lbm.businessId = :businessId and ld.test=true")
    Page<LeadData> findTestLeadsByBusinessId(@Param("businessId") int businessId, Pageable pageable);

    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId and ld.leadId =:leadId")
    Optional<LeadData> findByLeadIdAndBusinessId(int leadId, int businessId);


    @QueryHints(value = @QueryHint(name = HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId  AND ld.leadId IN :leadIds")
    List<LeadData> findExistingLeadIds(@Param("businessId") Integer businessId, @Param("leadIds") Set<Integer> leadIds);

    @QueryHints(value = @QueryHint(name = HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT ld FROM lead_data ld " +
            "JOIN business_lead lbm ON ld.leadId = lbm.leadId " +
            "WHERE lbm.businessId = :businessId")
    Stream<LeadData> findByLeadIdAndBusinessIdStream(@Param("businessId") Integer businessId);

}
