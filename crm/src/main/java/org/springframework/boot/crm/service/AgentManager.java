package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.AgentRequestDto;
import org.springframework.boot.crm.dto.AgentResponseDto;
import org.springframework.boot.crm.entity.AgentData;

import java.util.List;

public interface AgentManager {

     AgentResponseDto addAgent(AgentRequestDto agentRequestDto) ;

     List<AgentResponseDto> findByBusinessId(int businessId) ;

     AgentData findByBusinessIdAndAgentId(int businessId, int agentId) ;
}
