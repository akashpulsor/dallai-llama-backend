package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.AgentData;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentDataRepository extends JpaRepository<AgentData, Integer> {


    List<AgentData> findByBusinessId(Integer businessId);
}
