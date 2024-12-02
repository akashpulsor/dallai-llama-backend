package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class OnBoardingResponseDto {

    private int parentBusinessId;

    private int businessId;

    private String businessName;

    private boolean isPhoneGenerated;

    private OnBoardingDto.BusinessDetails businessDetails;

    private OnBoardingDto.Address address;

    private OnBoardingDto.BankDetails bankDetails;

    private TwilioSubAccountDto phoneData;

}
