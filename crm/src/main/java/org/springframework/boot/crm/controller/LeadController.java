package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.CampaignDataRequestDto;
import org.springframework.boot.crm.dto.CampaignDataResponseDto;
import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.service.CampaignManager;
import org.springframework.boot.crm.service.LeadManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/lead")
public class LeadController {

    private final LeadManager leadManager;

    public LeadController(LeadManager leadManager) {
        this.leadManager = leadManager;
    }


    @PostMapping("/add")
    public LeadResponseDto addCampaignData(@RequestBody LeadRequestDto leadRequestDto) {
        return this.leadManager.addLead(leadRequestDto);
    }

    @GetMapping("/list")
    public List<LeadResponseDto> getCampaignDataList(@RequestParam(value = "businessId") int businessId) {
        return this.leadManager.getLead(businessId);
    }

    @GetMapping("/{businessId}/leads/paginated")
    public Page<LeadResponseDto> getLeadsByBusinessIdPaginated(
            @PathVariable int businessId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "leadId") String sortBy) {
        return this.leadManager.getLeadsByBusinessIdPaginated(businessId,page, size, sortBy);
    }


}