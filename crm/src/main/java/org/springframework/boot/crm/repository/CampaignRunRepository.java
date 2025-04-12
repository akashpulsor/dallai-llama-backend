package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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


    CampaignRunData save(CampaignRunData campaignRunData);

    Optional<CampaignRunData> findByBusinessId(Integer businessId);

    @Query(value = "SELECT crl.lead_id FROM campaign_run_leads crl WHERE crl.campaign_run_id = :campaignRunId",
            nativeQuery = true)
    List<Integer> getLeadList(@Param("campaignRunId") Integer campaignRunId);

    @Query(value = "SELECT * FROM campaign_run_data crl WHERE crl.campaign_run_id = :campaignRunId and crl.business_id = :business_id",
            nativeQuery = true)
    Optional<CampaignRunData> findByCampaignRunIdAndBusinessId(@Param("campaignRunId") Integer campaignRunId,@Param("business_id") Integer businessId);

    Page<CampaignRunData> findByBusinessIdAndCampaignId(
            Integer businessId,
            Integer campaignId,
            Pageable pageable
    );

    @Query(value = "SELECT crl.lead_id FROM campaign_run_leads crl WHERE crl.campaign_run_id = :campaignRunId",
            countQuery = "SELECT COUNT(crl.lead_id) FROM campaign_run_leads crl WHERE crl.campaign_run_id = :campaignRunId",
            nativeQuery = true)
    Page<Integer> getPaginatedLeadList(@Param("campaignRunId") Integer campaignRunId, Pageable pageable);
}
