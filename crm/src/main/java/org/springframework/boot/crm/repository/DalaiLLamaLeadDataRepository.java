package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.DalaiLlamaLeads;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DalaiLLamaLeadDataRepository extends JpaRepository<DalaiLlamaLeads, Integer> {
    Optional<DalaiLlamaLeads> findByUniqueId(String uniqueId);

    // Add method to find by uniqueId, source, and campaign
    Optional<DalaiLlamaLeads> findByUniqueIdAndSourceAndCampaign(String uniqueId, String source, String campaign);
}
