package org.springframework.boot.crm.service;


import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public interface MetaManager {

    LlmData addLlmData(LlmData llmData);

    List<LlmData> getLlmData(int businessId);

    TwilioData addTwilioData(TwilioData twilioData);

    List<TwilioData> getTwilioData(int businessId);
}
