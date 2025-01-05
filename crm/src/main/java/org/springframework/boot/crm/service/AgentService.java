package org.springframework.boot.crm.service;


import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.AgentData;
import org.springframework.boot.crm.repository.AgentDataRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AgentService {


    private final AgentDataRepository agentDataRepository;

    public AgentService(AgentDataRepository agentDataRepository) {
        this.agentDataRepository = agentDataRepository;
    }


    public AgentData save(AgentData agentData) {
        return agentDataRepository.save(agentData);
    }

    public List<AgentData> findByBusinessId(int businessId) {
        return this.agentDataRepository.findByBusinessId(businessId);
    }

    public AgentData findByBusinessIdAndAgentId(int businessId, int agentId) {
        return agentDataRepository.findByBusinessIdAndAgentId(businessId, agentId).orElseThrow(() -> new EntityNotFoundException("AgentId not found"));
    }

}
