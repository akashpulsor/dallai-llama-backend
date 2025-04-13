package org.springframework.boot.crm.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.CampaignManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/campaign")
public class CampaignController {

    private final BusinessManager businessManager;

    public CampaignController(BusinessManager businessManager) {
        this.businessManager = businessManager;
    }
    @PostMapping("/add")
    public CampaignDataResponseDto addCampaignData(@RequestBody @Valid CampaignDataRequestDto campaignDataRequestDto) {
        return this.businessManager.getCampaignManager().add(campaignDataRequestDto);
    }

    @GetMapping("/list")
    public List<CampaignDataResponseDto> getCampaignDataList(@RequestParam(value = "businessId") int businessId) {
        return this.businessManager.getCampaignManager().getByBusinessId(businessId);
    }



    @GetMapping("/get")
    public CampaignDataResponseDto getBusinessData(@RequestParam(value = "businessId") int businessId,
                                        @RequestParam(value = "campaignId") int campaignId) {
        return this.businessManager.get(businessId, campaignId);
    }


    @PostMapping("/start")
    public CampaignRunResponseDto startCampaign(@RequestBody @Valid CampaignStartRequestDto campaignDataRequestDto) {
        return this.businessManager.startCampaign(campaignDataRequestDto);
    }

    @PostMapping("/run")
    public void runCampaign(@RequestParam(value = "businessId") int businessId,
                                              @RequestParam(value = "campaignRunId") int campaignRunId) {

        this.businessManager.runCampaign( campaignRunId, businessId );
    }

    @GetMapping("/run")
    public void getRunCampaign(@RequestParam(value = "businessId") int businessId,
                            @RequestParam(value = "campaignRunId") int campaignRunId) {

        this.businessManager.getCampaignManager().getCampaignRunData(campaignRunId, businessId);
    }

    @GetMapping("/{businessId}/{campaignId}/runs")
    public Page<CampaignRunData> getCampaignRuns(
            @PathVariable Integer businessId,
            @PathVariable Integer campaignId,
            Pageable pageable) {

        log.info("Fetching campaign runs for businessId: {} and campaignId: {}", businessId, campaignId);
        return businessManager.getCampaignManager()
                .getCampaignRunsByBusinessAndCampaign(businessId, campaignId, pageable);
    }

    @GetMapping("/{campaignRunId}/call-logs")
    public Page<CallLog> getCallLogs(
            @PathVariable int campaignRunId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching campaign runs for campaign run id: {}", campaignRunId);
        return this.businessManager.getCallManager().getPaginatedCallLogs(campaignRunId, page, size);
    }

    @GetMapping("/{callId}/usage-data")
    public PaymentDataDto getUsageData(@PathVariable int callId) {
        log.info("Fetching usage data for call id: {}", callId);
        return this.businessManager.getPaymentManager().getCallCharges(callId);
    }

    @GetMapping("/{campaignId}/usageData")
    public PaymentDataDto getUsageDataByCampaignId(@PathVariable int campaignId) {
        log.info("Fetching  usage data  for campaign  id: {}", campaignId);
        return this.businessManager.getPaymentManager().getCallChargesByCampaignId(campaignId);
    }

    @GetMapping("/{campaignRunId}/usageData")
    public PaymentDataDto getUsageDataByCampaignRunId(@PathVariable int campaignRunId) {
        log.info("Fetching  usage data  for campaign run id: {}", campaignRunId);
        return this.businessManager.getPaymentManager().getCallChargesByCampaignRunId(campaignRunId);
    }

    @GetMapping("/{businessId}/usageData")
    public PaymentDataDto getUsageDataByBusinessId(@PathVariable int businessId) {
        log.info("Fetching usage data for business id: {}", businessId);
        return this.businessManager.getPaymentManager().getCallChargesByBusinessId(businessId);
    }

}