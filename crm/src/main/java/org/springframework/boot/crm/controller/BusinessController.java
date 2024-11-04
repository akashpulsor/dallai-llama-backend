package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/business")
public class BusinessController {

    private final BusinessManager businessManager;

    public BusinessController(BusinessManager businessManager) {
        this.businessManager = businessManager;
    }
    @PostMapping("/add")
    public BusinessData addBusinessData(@RequestBody BusinessData BusinessData) {
        return this.businessManager.addBusiness(BusinessData);
    }

    @GetMapping("/get")
    public BusinessData getBusinessData(@RequestParam(value = "businessId") int businessId) {
        return this.businessManager.getBusinessData(businessId);
    }

    @PostMapping("/add-llm")
    public LlmData addBusinessData(@RequestBody LlmData LlmData) {
        return this.businessManager.addLlmData(LlmData);
    }

    @GetMapping("/llm")
    public List<LlmData> getLlmData() {
        return this.businessManager.getAllLlmData();
    }

    @PostMapping("/add-twilio")
    public TwilioData addBusinessData(@RequestBody TwilioData twilioData) {
        return this.businessManager.addTwilioData(twilioData);
    }

    @GetMapping("/twilio")
    public List<TwilioData> getTwilioData() {
        return this.businessManager.getAllTwilioData();
    }
}