package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;

import java.util.List;
import java.util.stream.Collectors;

public interface BusinessManager {
    BusinessData getBusinessData(int businessId);

    BusinessData addBusiness(BusinessData businessData);


    BusinessData getBusinessByEmail(String email);

    BusinessData getBusinessByMobile(String mobile);

    boolean checkEmailExists(String email);

    boolean checkPhoneExists(String phone);

    List<BusinessSizeMasterDataDto> getAllBusinessSizeMasterData();

    OnBoardingResponseDto onBoardBusiness(OnBoardingDto onBoardingDto);
    OnBoardingResponseDto getOnBoardBusiness(int businessId);

    TwilioSubAccountDto generateNumber(GenerateNumberRequestDto generateNumberRequestDto);

    CampaignManager getCampaignManager();

    AgentManager getAgentManager();

    LeadManager getLeadManager();

    LlmData addLlmData(LlmData llmData);

    TwilioData addTwilioData(TwilioData twilioData);

    List<LlmData> getAllLlmData(int businessId);

    List<TwilioData> getAllTwilioData(int businessId);

    MetaManager getMetaManager();

    CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto);

    CampaignDataResponseDto get(int campaignId, int businessId);

    List<CampaignDataResponseDto> getByBusinessId(int  businessId);

    void runCampaign(int campaignRunId, int businessId);

    AgentResponseDto addAgent(AgentRequestDto agentRequestDto);

    List<AgentResponseDto> findByBusinessId(int businessId);
}
