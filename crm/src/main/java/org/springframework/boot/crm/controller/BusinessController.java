package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.GenerateNumberRequestDto;
import org.springframework.boot.crm.dto.OnBoardingDto;
import org.springframework.boot.crm.dto.OnBoardingResponseDto;
import org.springframework.boot.crm.dto.TwilioSubAccountDto;
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

    @PostMapping("/onboard")
    public OnBoardingResponseDto onBoardBusiness(@RequestBody OnBoardingDto onBoardingDto) {
        return businessManager.onBoardBusiness(onBoardingDto);
    }

    @GetMapping("/onboard")
    public OnBoardingResponseDto getOnBoardBusinessData(@RequestParam(value = "businessId") int businessId) {
        return businessManager.getOnBoardBusiness(businessId);
    }

    @PostMapping("/generate-number")
    public TwilioSubAccountDto generateNumber(GenerateNumberRequestDto generateNumberRequestDto) {
        return businessManager.generateNumber(generateNumberRequestDto);
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

    @PostMapping("/generate-phone")
    public TwilioData addGeneratePhoneData(@RequestBody TwilioData twilioData) {
        return this.businessManager.addTwilioData(twilioData);
    }

    @GetMapping("/twilio")
    public List<TwilioData> getTwilioData() {
        return this.businessManager.getAllTwilioData();
    }
}