package org.springframework.boot.crm.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BusinessManagerImpl implements BusinessManager {
    private final BusinessService businessService;

    private final BusinessApiKeyService businessApiKeyService;

    private final MasterDataService masterDataService;

    private final PhoneService phoneService;

    private final BusinessIndiaService businessIndiaService;
    private String number;

    public BusinessManagerImpl( BusinessService businessService,
                               BusinessApiKeyService businessApiKeyService,
                                MasterDataService masterDataService,
                                PhoneService phoneService,
                                BusinessIndiaService businessIndiaService){
        this.businessService = businessService;
        this.businessApiKeyService = businessApiKeyService;
        this.masterDataService = masterDataService;
        this.phoneService = phoneService;
        this.businessIndiaService = businessIndiaService;
        this.number = "+1 901 979 1433";
    }
    @Override
    public BusinessData getBusinessData(int businessId) {
        return this.businessService.getBusinessDataById(businessId);
    }

    @Override
    public BusinessData addBusiness(BusinessData businessData) {
        return this.businessService.addBusiness(businessData);
    }

    @Transactional
    public OnBoardingResponseDto onBoardBusiness(OnBoardingDto onBoardingDto){
        BusinessDataIndia businessDataIndiaModel = businessDataIndiaModelToModel(onBoardingDto);
        BusinessData businessData = getBusinessData(onBoardingDto.getParentBusinessId());
        BusinessDataIndia businessDataIndia = businessIndiaService.addBusinessData(businessDataIndiaModel);
        return getOnBoardingResponseDto(businessData, businessDataIndia, createBusinessDetailsFromModels(businessDataIndiaModel));
    }

    public OnBoardingResponseDto getOnBoardBusiness(int businessId){
        BusinessData businessData = getBusinessData(businessId);
        BusinessDataIndia businessDataIndia = businessIndiaService.getBusinessData(businessId);
        return getOnBoardingResponseDto(businessData,  businessDataIndia, createBusinessDetailsFromModels(businessDataIndia));
    }

    @Override
    public TwilioSubAccountDto generateNumber(GenerateNumberRequestDto generateNumberRequestDto) {
        BusinessData businessData = getBusinessData(generateNumberRequestDto.getBusinessId());
        TwilioData twilioData = new TwilioData();
        twilioData.setFriendlyName(businessData.getBusinessName());
        return this.phoneService.createSubAccount(twilioData);
    }


    private OnBoardingDto.BusinessDetails createBusinessDetailsFromModels(BusinessDataIndia businessDataIndia){
        OnBoardingDto.BusinessDetails businessDetails = new OnBoardingDto.BusinessDetails();
        if(businessDataIndia.getAdhaarNumber()!=null) businessDetails.setAdhaarNumber(businessDataIndia.getAdhaarNumber());
        if(businessDataIndia.getGstIn()!=null)  businessDetails.setGstNumber(businessDataIndia.getGstIn());
        if(businessDataIndia.getUdyamRegistrationNumber()!=null) businessDetails.setUdyamRegistrationNumber(businessDataIndia.getUdyamRegistrationNumber());
        if(businessDataIndia.getPan()!=null) businessDetails.setPan(businessDataIndia.getPan());
        if(businessDataIndia.getPhone()!=null) businessDetails.setPhone(businessDataIndia.getPhone());
        if(businessDataIndia.getAddress()!=null) businessDetails.setCountryCode(businessDataIndia.getAddress().getCountryCode());
        return businessDetails;
    }

    private OnBoardingResponseDto getOnBoardingResponseDto(BusinessData businessData,  BusinessDataIndia businessDataIndia, OnBoardingDto.BusinessDetails businessDetailsFromModels) {
        OnBoardingResponseDto onBoardingResponseDto = new OnBoardingResponseDto();
        onBoardingResponseDto.setBusinessId(businessDataIndia.getBusinessId());
        onBoardingResponseDto.setParentBusinessId(businessData.getBusinessId());
        if(businessData.getBusinessName()!= null)  onBoardingResponseDto.setBusinessName(businessData.getBusinessName());
        onBoardingResponseDto.setBusinessDetails(businessDetailsFromModels);
        onBoardingResponseDto.setPhoneGenerated(businessDataIndia.isPhoneGenerated());
        if(businessDataIndia.getAddress()!= null) onBoardingResponseDto.setAddress(getAddress(businessDataIndia.getAddress()));
        if(businessDataIndia.getBankDetails()!= null) onBoardingResponseDto.setBankDetails(getBankDetails(businessDataIndia.getBankDetails()));
        return onBoardingResponseDto;
    }

    private OnBoardingDto.BankDetails getBankDetails(BankDetails bankDetailsModel) {
        OnBoardingDto.BankDetails bankDetails = new OnBoardingDto.BankDetails();
        bankDetails.setBankName(bankDetailsModel.getBankName());
        bankDetails.setAccountHolderName(bankDetailsModel.getAccountHolderName());
        bankDetails.setIfscCode(bankDetailsModel.getIfscCode());
        bankDetails.setAccountNumber(bankDetailsModel.getAccountNumber());
        bankDetails.setBranchName(bankDetails.getBranchName());
        return bankDetails;
    }

    private OnBoardingDto.Address getAddress(Address addressModel) {
        OnBoardingDto.Address address = new OnBoardingDto.Address();
        address.setStreetAddress(addressModel.getStreet());
        address.setApartment(addressModel.getApartment());
        OnBoardingDto.Region countryRegion =  new OnBoardingDto.Region();
        countryRegion.setIsoCode(addressModel.getCountryCode());
        address.setCountry(countryRegion);
        OnBoardingDto.Region stateRegion =  new OnBoardingDto.Region();
        stateRegion.setIsoCode(addressModel.getState());
        address.setState(stateRegion);
        address.setFormattedAddress(addressModel.getFormattedAddress());
        address.setPostalCode(addressModel.getZipCode());
        OnBoardingDto.Region cityRegion =  new OnBoardingDto.Region();
        cityRegion.setIsoCode(addressModel.getState());
        address.setCity(cityRegion);
        return address;
    }


    @Override
    public LlmData addLlmData(LlmData llmData) {
        return this.businessApiKeyService.addLlmData(llmData);
    }

    @Override
    public TwilioData addTwilioData(TwilioData twilioData) {
        return this.businessApiKeyService.addTwilioData(twilioData);
    }

    public BusinessData getBusinessByEmail(String email) {
        return this.businessService.getBusinessDataByEmail(email);
    }

    public BusinessData getBusinessByMobile(String mobile) {
        return this.businessService.getBusinessDataByMobile(mobile);
    }

    @Override
    public boolean checkEmailExists(String email) {
        return this.businessService.checkEmailExists(email);
    }

    @Override
    public boolean checkPhoneExists(String phone) {
        return this.businessService.checkPhoneExists(phone);
    }

    @Override
    public List<BusinessSizeMasterDataDto> getAllBusinessSizeMasterData() {
        return this.masterDataService.getAllBusinessSize().stream().map(this::businessSizeMasterDataModelToDto).toList();
    }

    private BusinessSizeMasterDataDto businessSizeMasterDataModelToDto(BusinessSizeMasterData businessSizeMasterData) {
        BusinessSizeMasterDataDto businessSizeMasterDataDto = new BusinessSizeMasterDataDto();
        businessSizeMasterDataDto.setValue(businessSizeMasterData.getId());
        businessSizeMasterDataDto.setLabel(businessSizeMasterData.getLabel());
        return  businessSizeMasterDataDto;
    }


    @Override
    public List<LlmData> getAllLlmData() {
        return this.businessApiKeyService.getLlmData();
    }

    @Override
    public List<TwilioData> getAllTwilioData() {
        return this.businessApiKeyService.getTwilioData();
    }

    private BusinessDataIndia businessDataIndiaModelToModel(OnBoardingDto onBoardingDto) {
        BusinessDataIndia businessDataIndiaModel =this.businessIndiaService.getBusinessData(onBoardingDto.getParentBusinessId());
        businessDataIndiaModel.setParentBusinessId(onBoardingDto.getParentBusinessId());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getPan()!= null)
                    businessDataIndiaModel.setPan(onBoardingDto.getBusinessDetails().getPan());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getAdhaarNumber()!= null) businessDataIndiaModel.setAdhaarNumber(onBoardingDto.getBusinessDetails().getAdhaarNumber());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getGstNumber()!= null) businessDataIndiaModel.setGstIn(onBoardingDto.getBusinessDetails().getGstNumber());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getUdyamRegistrationNumber()!= null) businessDataIndiaModel.setUdyamRegistrationNumber(onBoardingDto.getBusinessDetails().getUdyamRegistrationNumber());
        businessDataIndiaModel.setActive(true);
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getPhone()!= null) businessDataIndiaModel.setPhone(onBoardingDto.getBusinessDetails().getPhone());
        businessDataIndiaModel.setAddress(addressDtoToModel(onBoardingDto.getAddress()));
        if(onBoardingDto.getBankDetails() != null)
        {
            businessDataIndiaModel.setBankDetails(bankDetailsDtoToModel(onBoardingDto.getBankDetails()));
        };
        return businessDataIndiaModel;
    }

    private Address addressDtoToModel(OnBoardingDto.Address addressDto){
        Address addressModel = new Address();
        if(addressDto.getStreetAddress()!=null) addressModel.setStreet(addressDto.getStreetAddress());
        if(addressDto.getApartment()!=null)  addressModel.setApartment(addressDto.getApartment());
        if(addressDto.getCity()!=null) addressModel.setCity(addressDto.getCity().getName());
        if(addressDto.getState()!=null) addressModel.setState(addressDto.getState().getIsoCode());
        if(addressDto.getPostalCode()!=null) addressModel.setZipCode(addressDto.getPostalCode());
        if(addressDto.getCountry()!=null) addressModel.setCountryCode(addressDto.getCountry().getIsoCode());
        if(addressDto.getFormattedAddress()!=null) addressModel.setFormattedAddress(addressDto.getFormattedAddress());
        return addressModel;
    }

    private BankDetails bankDetailsDtoToModel(OnBoardingDto.BankDetails bankDetailsDto){
        BankDetails bankDetails = new BankDetails();
        if(bankDetailsDto.getBranchName()!=null) bankDetails.setBankBranch(bankDetailsDto.getBranchName());
        if(bankDetailsDto.getBankName()!=null) bankDetails.setBankName(bankDetailsDto.getBankName());
        if(bankDetailsDto.getAccountNumber()!=null)  bankDetails.setAccountNumber(bankDetailsDto.getAccountNumber());
        if(bankDetailsDto.getIfscCode()!=null) bankDetails.setIfscCode(bankDetailsDto.getIfscCode());
        if(bankDetailsDto.getAccountHolderName()!=null) bankDetails.setAccountHolderName(bankDetailsDto.getAccountHolderName());
        return bankDetails;
    }


}
