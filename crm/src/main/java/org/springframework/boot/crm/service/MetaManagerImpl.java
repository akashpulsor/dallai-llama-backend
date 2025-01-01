package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MetaManagerImpl implements  MetaManager {

    private LlmService llmService;

    private TwilioService twilioService;

    public MetaManagerImpl(LlmService llmService,TwilioService twilioService) {
        this.llmService = llmService;
        this.twilioService = twilioService;
    }
    @Override
    public LlmData addLlmData(LlmData llmData) {
        return this.llmService.addLlmData(llmData);
    }

    public LlmData getLlmData(int businessId, int llmId) {
        return this.llmService.getLlmData(businessId, llmId);
    }

    public TwilioData getTwilioData(int businessId, int phoneId) {
        return this.twilioService.getTwilioData(businessId, phoneId);
    }

    public List<LlmData> getLlmData(int businessId) {
        return this.llmService.getLlmData(businessId);
    }

    public TwilioData addTwilioData(TwilioData twilioData) {
        return this.twilioService.addTwilioData(twilioData);
    }

    public List<TwilioData> getTwilioData(int businessId) {
        return this.twilioService.getTwilioData(businessId);
    }
}
