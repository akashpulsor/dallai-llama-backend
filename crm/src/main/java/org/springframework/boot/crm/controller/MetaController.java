package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.DashBoardDataDto;
import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
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
    private final MetaManager metaManager;

    public MetaController(MetaManager metaManager) {
        this.metaManager = metaManager;
    }

    @PostMapping("/llm/add")
    public LlmData addLlmData(@RequestBody LlmData llmData) {
        return this.metaManager.addLlmData(llmData);
    }

    @GetMapping("/llm/{businessId}/get")
    public List<LlmData> getLlmData(@PathVariable int businessId) {
        return this.metaManager.getLlmData(businessId);
    }

    @PostMapping("/phone/add")
    public TwilioData addPhoneData(@RequestBody TwilioData twilioData) {
        return this.metaManager.addTwilioData(twilioData);
    }

    @GetMapping("/phone/{businessId}/get")
    public List<TwilioData> getPhoneData(@PathVariable int businessId) {
        return this.metaManager.getTwilioData(businessId);
    }


    @GetMapping("/dashboard")
    public DashBoardDataDto getDashboardData(
            @RequestParam(value = "businessId") int businessId,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDateTime startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDateTime endDate) {

        return new DashBoardDataDto(0,0,0,0,0);
    }



}
