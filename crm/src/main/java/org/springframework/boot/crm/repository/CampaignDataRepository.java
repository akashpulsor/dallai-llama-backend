package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignDataRepository extends JpaRepository<CampaignData, Integer> {

    Optional<CampaignData> findByCampaignIdAndBusinessId(Integer campaignId, Integer businessId);

    CampaignData save(CampaignData campaignData);

    List<CampaignData> findByBusinessId(Integer businessId);
}
