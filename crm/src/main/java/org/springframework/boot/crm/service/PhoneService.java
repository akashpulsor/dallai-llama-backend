package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.TwilioSubAccountDto;
import org.springframework.boot.crm.entity.TwilioData;

import java.util.List;

public interface PhoneService {



    List<TwilioSubAccountDto> listSubAccounts();

    TwilioSubAccountDto suspendSubAccount(String sid) ;

    TwilioSubAccountDto closeSubAccount(String sid);

    TwilioSubAccountDto createSubAccount(TwilioData twilioData);

    TwilioSubAccountDto getSubAccount(int parentBusinessId);

    TwilioData addTwilioData(TwilioData twilioData);

    List<TwilioData> getTwilioData(int businessId);
}
