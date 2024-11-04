package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.boot.crm.exceptions.LlmDataNotFoundException;
import org.springframework.boot.crm.exceptions.TwilioDataNotFoundException;
import org.springframework.boot.crm.repository.LlmDataRepository;
import org.springframework.boot.crm.repository.TwilioDataRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class BusinessApiKeyService {
    private final LlmDataRepository llmDataRepository;

    private final TwilioDataRepository twilioDataRepository;

    public BusinessApiKeyService(LlmDataRepository llmDataRepository,TwilioDataRepository twilioDataRepository) {
        this.llmDataRepository = llmDataRepository;
        this.twilioDataRepository = twilioDataRepository;
    }

    public TwilioData getTwilioKey(int businessId){
        log.info("getTwilioKey: businessId={}", businessId);
        return this.twilioDataRepository.findByBusinessId(businessId).orElseThrow(()->new TwilioDataNotFoundException("Twilio Data Not Found"));
    }



    public LlmData getOpenAiKey(int businessId){
        log.info("getOpenAiKey: businessId={}", businessId);
        return this.llmDataRepository.findByBusinessId(businessId).orElseThrow(()->new LlmDataNotFoundException("Llm Data Not Found"));
    }


    public List<LlmData> getLlmData(){
        return llmDataRepository.findAll();
    }

    public List<TwilioData> getTwilioData(){
        return twilioDataRepository.findAll();
    }
    public LlmData addLlmData(LlmData llmData){
        return llmDataRepository.save(llmData);
    }

    public TwilioData addTwilioData(TwilioData twilioData){
        return twilioDataRepository.save(twilioData);
    }
}
