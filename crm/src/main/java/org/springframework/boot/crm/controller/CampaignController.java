package org.springframework.boot.crm.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.CampaignManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/campaign")
public class CampaignController {

    private final CampaignManager campaignManager;

    public CampaignController(CampaignManager campaignManager) {
        this.campaignManager = campaignManager;
    }
    @PostMapping("/add")
    public CampaignDataResponseDto addCampaignData(@RequestBody @Valid CampaignDataRequestDto campaignDataRequestDto) {
        return this.campaignManager.add(campaignDataRequestDto);
    }

    @GetMapping("/list")
    public List<CampaignDataResponseDto> getCampaignDataList(@RequestParam(value = "businessId") int businessId) {
        return this.campaignManager.getByBusinessId(businessId);
    }



    @GetMapping("/get")
    public CampaignDataResponseDto getBusinessData(@RequestParam(value = "businessId") int businessId,
                                        @RequestParam(value = "campaignId") int campaignId) {
        return this.campaignManager.get(businessId, campaignId);
    }


    @PostMapping("/start")
    public CampaignStartResponseDto startCampaign(@RequestBody @Valid CampaignStartRequestDto campaignDataRequestDto) {
        return this.campaignManager.start(campaignDataRequestDto);
    }

    @PostMapping("/run")
    public CampaignRunResponseDto runCampaign(@RequestBody @Valid CampaignRunRequestDto campaignDataRequestDto) {
        return null;
    }
}