package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.DalaiLlamaLeads;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DalaiLLamaLeadDataRepository extends JpaRepository<DalaiLlamaLeads, Integer> {


}
