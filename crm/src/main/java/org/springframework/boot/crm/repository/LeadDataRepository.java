package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadDataRepository extends JpaRepository<LeadData, Integer> {

    LeadData findByLeadId(Integer leadId);


}
