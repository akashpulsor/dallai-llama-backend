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


    public AgentManagerImpl(AgentService agentService) {
        this.agentService = agentService;
    }
    @Override
    public AgentResponseDto addAgent(AgentRequestDto agentRequestDto) {
        return modelToDto(this.agentService.save(dtoToModel(agentRequestDto)));
    }

    @Override
    public List<AgentResponseDto> findByBusinessId(int businessId) {
        return this.agentService.findByBusinessId(businessId).stream().
                map(this::modelToDto).collect(Collectors.toList());
    }

    @Override
    public AgentData findByBusinessIdAndAgentId(int businessId, int agentId) {
        return this.agentService.findByBusinessIdAndAgentId(businessId, agentId);
    }

    private AgentData dtoToModel(AgentRequestDto agentRequestDto) {
        AgentData agentData = new AgentData();
        agentData.setAgentId(agentRequestDto.getAgentId());
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
        agentResponseDto.setAgentId(agentData.getAgentId());
        return agentResponseDto;
    }
}
