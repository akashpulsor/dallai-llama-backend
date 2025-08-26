package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.InboundCampaignData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InboundCampaignDataRepository  extends JpaRepository<InboundCampaignData, Integer> {

    List<InboundCampaignData> findByBusinessId(int businessId);
    List<InboundCampaignData> findByIsActiveTrue();

    Optional<InboundCampaignData> findByCampaignIdAndBusinessId(Integer campaignId, Integer businessId);

}
