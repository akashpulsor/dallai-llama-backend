package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.DashBoardDataDto;
import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.MetaManager;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/meta")
public class MetaController {
    private final BusinessManager businessManager;

    public MetaController(BusinessManager businessManager) {
        this.businessManager = businessManager;
    }

    @PostMapping("/llm/add")
    public LlmData addLlmData(@RequestBody LlmData llmData) {
        return this.businessManager.addLlmData(llmData);
    }

    @GetMapping("/llm/{businessId}/get")
    public List<LlmData> getLlmData(@PathVariable int businessId) {
        return this.businessManager.getMetaManager().getLlmData(businessId);
    }

    @PostMapping("/phone/add")
    public TwilioData addPhoneData(@RequestBody TwilioData twilioData) {
        return this.businessManager.getMetaManager().addTwilioData(twilioData);
    }

    @GetMapping("/phone/{businessId}/get")
    public List<TwilioData> getPhoneData(@PathVariable int businessId) {
        return this.businessManager.getMetaManager().getTwilioData(businessId);
    }


    @GetMapping("/dashboard")
    public DashBoardDataDto getDashboardData(
            @RequestParam(value = "businessId") int businessId,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        return this.businessManager.getDashBoardDto(startDate, endDate,businessId);
    }



}
