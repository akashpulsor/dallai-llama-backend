package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.AgentRequestDto;
import org.springframework.boot.crm.dto.AgentResponseDto;
import org.springframework.boot.crm.entity.AgentData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AgentManagerImpl implements AgentManager {

    private final AgentService agentService;

    private final BusinessManager businessManager;

    public AgentManagerImpl(AgentService agentService, BusinessManager businessManager) {
        this.agentService = agentService;
        this.businessManager = businessManager;
    }
    @Override
    public AgentResponseDto addAgent(AgentRequestDto agentRequestDto) {
        businessManager.getBusinessData(agentRequestDto.getBusinessId());
        return modelToDto(this.agentService.save(dtoToModel(agentRequestDto)));
    }

    @Override
    public List<AgentResponseDto> findByBusinessId(int businessId) {
        businessManager.getBusinessData(businessId);
        return this.agentService.findByBusinessId(businessId).stream().
                map(this::modelToDto).collect(Collectors.toList());
    }

    private AgentData dtoToModel(AgentRequestDto agentRequestDto) {
        AgentData agentData = new AgentData();
        agentData.setBusinessId(agentRequestDto.getBusinessId());
        agentData.setAgentName(agentRequestDto.getAgentName());
        agentData.setRole(agentRequestDto.getRole());
        agentData.setPersona(agentRequestDto.getPersona());
        agentData.setVoice(agentRequestDto.getVoice());
        agentData.setActive(true);
        return agentData;
    }

    private AgentResponseDto modelToDto(AgentData agentData) {
        AgentResponseDto agentResponseDto = new AgentResponseDto();
        agentResponseDto.setBusinessId(agentData.getBusinessId());
        agentResponseDto.setAgentName(agentData.getAgentName());
        agentResponseDto.setRole(agentData.getRole());
        agentResponseDto.setPersona(agentData.getPersona());
        agentResponseDto.setVoice(agentData.getVoice());
        agentResponseDto.setActive(agentData.isActive());
        return agentResponseDto;
    }
}
