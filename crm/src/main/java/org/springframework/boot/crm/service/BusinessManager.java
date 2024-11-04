package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;

import java.util.List;

public interface BusinessManager {
    BusinessData getBusinessData(int businessId);

    BusinessData addBusiness(BusinessData businessData);

    LlmData addLlmData(LlmData llmData);

    TwilioData addTwilioData(TwilioData twilioData);

    List<LlmData> getAllLlmData();

    List<TwilioData> getAllTwilioData();
}
