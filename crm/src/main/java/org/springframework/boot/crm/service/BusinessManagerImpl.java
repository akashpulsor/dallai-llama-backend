package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BusinessManagerImpl implements BusinessManager {
    private final BusinessService businessService;

    private final BusinessApiKeyService businessApiKeyService;

    public BusinessManagerImpl( BusinessService businessService,
                               BusinessApiKeyService businessApiKeyService){
        this.businessService = businessService;
        this.businessApiKeyService = businessApiKeyService;
    }
    @Override
    public BusinessData getBusinessData(int businessId) {
        return this.businessService.getBusinessDataById(businessId);
    }

    @Override
    public BusinessData addBusiness(BusinessData businessData) {
        return this.businessService.addBusiness(businessData);
    }


    @Override
    public LlmData addLlmData(LlmData llmData) {
        return this.businessApiKeyService.addLlmData(llmData);
    }

    @Override
    public TwilioData addTwilioData(TwilioData twilioData) {
        return this.businessApiKeyService.addTwilioData(twilioData);
    }

    @Override
    public List<LlmData> getAllLlmData() {
        return this.businessApiKeyService.getLlmData();
    }

    @Override
    public List<TwilioData> getAllTwilioData() {
        return this.businessApiKeyService.getTwilioData();
    }
}
