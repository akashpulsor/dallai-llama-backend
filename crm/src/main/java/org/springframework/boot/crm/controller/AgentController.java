package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.AgentRequestDto;
import org.springframework.boot.crm.dto.AgentResponseDto;
import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.service.AgentManager;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.LeadManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final BusinessManager businessManager;

    public AgentController(BusinessManager businessManager) {
        this.businessManager = businessManager;
    }
    @PostMapping("/add")
    public AgentResponseDto addAgent(@RequestBody AgentRequestDto agentRequestDto) {
        return this.businessManager.addAgent(agentRequestDto);
    }

    @GetMapping("/list")
    public List<AgentResponseDto> getCampaignDataList(@RequestParam(value = "businessId") int businessId) {
        return this.businessManager.findByBusinessId(businessId);
    }



}