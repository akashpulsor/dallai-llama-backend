package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.BusinessSizeMasterDataDto;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
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

    BusinessData getBusinessByEmail(String email);

    BusinessData getBusinessByMobile(String mobile);

    boolean checkEmailExists(String email);

    boolean checkPhoneExists(String phone);

    List<BusinessSizeMasterDataDto> getAllBusinessSizeMasterData();

}
