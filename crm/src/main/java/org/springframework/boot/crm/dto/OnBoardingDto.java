package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class OnBoardingDto {

    private int parentBusinessId;

    private BusinessDetails businessDetails;

    private Address address;

    private BankDetails bankDetails;

    @Data
    public static class BusinessDetails {
        private String udyamRegistrationNumber;
        private String pan;
        private String adhaarNumber;
        private String  gstNumber;
        private String  phone;
        private String  countryCode;
    }


    @Data
    public static class Address {
        private  Region country;
        private  Region state;
        private Region city;
        private String streetAddress;
        private String postalCode;
        private String apartment;
        private String formattedAddress;
    }

    @Data
    public static class BankDetails {
        private String accountHolderName;
        private String accountNumber;
        private String ifscCode;
        private String bankName;
        private String branchName;
    }

    @Data
    public static class Region {
        private String name;
        private String isoCode;
    }

}
