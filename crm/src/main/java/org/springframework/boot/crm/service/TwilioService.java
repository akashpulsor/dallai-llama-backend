package org.springframework.boot.crm.service;

import com.twilio.base.ResourceSet;
import com.twilio.rest.api.v2010.Account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.TwilioSubAccountDto;

import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.boot.crm.repository.TwilioDataRepository;
import org.springframework.stereotype.Service;
import com.twilio.Twilio;


import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class TwilioService implements  PhoneService {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    private TwilioDataRepository twilioDataRepository;

    public TwilioService(TwilioDataRepository twilioDataRepository) {
        this.twilioDataRepository = twilioDataRepository;
    }




    private TwilioSubAccountDto createSubAccount(String friendlyName) {
        Twilio.init(accountSid, authToken);
        Account account = Account.creator()
                .setFriendlyName(friendlyName)
                .create();

        return convertToDto(account);
    }

    @Override
    public List<TwilioSubAccountDto> listSubAccounts() {
        ResourceSet<Account> accounts = Account.reader().read();
        List<TwilioSubAccountDto> accountList = new ArrayList<>();

        for (Account account : accounts) {
            accountList.add(convertToDto(account));
        }

        return accountList;
    }

    @Override
    public TwilioSubAccountDto suspendSubAccount(String sid) {
        Account account = Account.updater(sid)
                .setStatus(Account.Status.SUSPENDED)
                .update();

        return convertToDto(account);
    }

    @Override
    public TwilioSubAccountDto closeSubAccount(String sid) {
        Account account = Account.updater(sid)
                .setStatus(Account.Status.CLOSED)
                .update();

        return convertToDto(account);
    }

    @Override
    public TwilioSubAccountDto createSubAccount(TwilioData data) {
        TwilioSubAccountDto twilioSubAccountDto = createSubAccount(data.getFriendlyName());
        TwilioData twilioData = convertToDto(twilioSubAccountDto);
        twilioData.setBusinessId(data.getBusinessId());
        twilioData = this.twilioDataRepository.save(twilioData);
        return convertToDto(twilioData);
    }

    @Override
    public TwilioSubAccountDto getSubAccount(int parentBusinessId) {
        TwilioData twilioData = this.twilioDataRepository.
                findByBusinessId(parentBusinessId).
                orElse(new TwilioData());
        return convertToDto(twilioData);
    }



    private TwilioSubAccountDto convertToDto(Account account) {
        TwilioSubAccountDto dto = new TwilioSubAccountDto();
        dto.setSid(account.getSid());
        dto.setFriendlyName(account.getFriendlyName());
        dto.setAuthToken(account.getAuthToken());
        if(account.getStatus()!=null)dto.setStatus(account.getStatus().toString());
        if(account.getDateCreated()!=null)dto.setDateCreated(account.getDateCreated().toString());
        return dto;
    }


    private TwilioData convertToDto(TwilioSubAccountDto twilioSubAccountDto) {
        TwilioData twilioData = new TwilioData();
        twilioData.setAccountSid(twilioSubAccountDto.getSid());
        twilioData.setFriendlyName(twilioSubAccountDto.getFriendlyName());
        twilioData.setAccountAuthToken(twilioSubAccountDto.getAuthToken());
        twilioData.setStatus(twilioSubAccountDto.getStatus());
        twilioData.setActive(true);
        return twilioData;
    }

    private TwilioSubAccountDto convertToDto(TwilioData twilioData) {
        TwilioSubAccountDto dto = new TwilioSubAccountDto();
        dto.setSid(twilioData.getAccountSid());
        dto.setFriendlyName(twilioData.getFriendlyName());
        dto.setAuthToken(twilioData.getAccountAuthToken());
        dto.setStatus(twilioData.getStatus());
        if(twilioData.getSanitaryColumn()!=null) dto.setDateCreated(twilioData.getSanitaryColumn().getCreatedAt().toString());
        dto.setBusinessId(twilioData.getBusinessId());
        dto.setPhoneId(twilioData.getTwilioId());
        return dto;
    }



}
