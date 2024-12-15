package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface CampaignRunRepository  extends JpaRepository<CampaignRunData, Integer> {

    @Modifying
    @Query(value = "INSERT INTO campaign_run_leads (campaign_run_id, lead_id) VALUES (:campaignRunId, :leadId)",
            nativeQuery = true)
    void insertLead(@Param("campaignRunId") int campaignRunId, @Param("leadId") int leadId);

    default void insertLeadsBatch(int campaignRunId, Set<Integer> leads) {
        // You might want to use JDBC batch operations here for better performance
        leads.forEach(leadId -> insertLead(campaignRunId, leadId));
    }



    Optional<CampaignRunData> findByBusinessId(Integer businessId);
}
