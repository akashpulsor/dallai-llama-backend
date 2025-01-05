package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.dto.OnBoardingDto;
import org.springframework.boot.crm.entity.Address;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class LeadManagerImpl implements LeadManager {

    private final LeadDataService leadDataService;

    public LeadManagerImpl(LeadDataService leadDataService) {
        this.leadDataService = leadDataService;
    }

    @Override
    public LeadResponseDto addLead(LeadRequestDto leadRequestDto) {
        LeadData leadData = convertDtoToModel(leadRequestDto);
        leadData = this.leadDataService.save(leadData);
        this.leadDataService.addBusinessLead(leadData.getLeadId(), leadRequestDto.getBusinessId());
        return convertModelToDto(leadData);
    }

    public List<LeadResponseDto> getLead(int businessId) {
        return this.leadDataService.getLeadByBusinessId(businessId).
                stream().
                map(this::convertModelToDto).
                collect(Collectors.toList());
    }



    @Override
    public Page<LeadResponseDto> getLeadsByBusinessIdPaginated(int businessId, int page, int size, String sortBy, boolean test) {
        log.info("Fetching paginated leads for business: {}, page: {}, size: {}", businessId, page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        if(test){
            return leadDataService.getTestLeadsByBusinessIdPaginated(businessId, pageable).map(lead -> convertModelToDto1(lead, businessId));
        }
        return leadDataService.getLeadsByBusinessIdPaginated(businessId, pageable).map(lead -> convertModelToDto1(lead, businessId));
    }

    @Override
    public Stream<LeadData> getLeadDataByStream(int businessId) {
        return this.leadDataService.getLeadDataByStream(businessId);
    }

    public LeadResponseDto getLead(int businessId, int leadId) {
        return convertModelToDto(this.leadDataService.getBusinessLead(businessId,leadId));
    }

    @Override
    public LeadData getLeadData(int businessId, int leadId) {
        return this.leadDataService.getBusinessLead(businessId,leadId);
    }

    @Override
    public List<LeadData> getLeadDataByList( Integer businessId,  Set<Integer> leadIds) {
        return this.leadDataService.getLeadDataByStream(businessId, leadIds);
    }



    private LeadResponseDto convertModelToDto(LeadData leadData) {
        LeadResponseDto leadResponseDto = new LeadResponseDto();
        leadResponseDto.setLeadId(leadData.getLeadId());
        leadResponseDto.setLeadName(leadData.getLeadName());
        leadResponseDto.setLeadEmail(leadData.getLeadEmail());
        leadResponseDto.setLeadPhone(leadData.getLeadPhone());
        leadResponseDto.setLeadGender(leadResponseDto.getLeadGender());
        return leadResponseDto;
    }

    private LeadResponseDto convertModelToDto1(LeadData leadData, int businessId) {
        LeadResponseDto leadResponseDto = new LeadResponseDto();
        leadResponseDto.setBusinessId(businessId);
        leadResponseDto.setLeadId(leadData.getLeadId());
        leadResponseDto.setLeadName(leadData.getLeadName());
        leadResponseDto.setLeadEmail(leadData.getLeadEmail());
        leadResponseDto.setLeadPhone(leadData.getLeadPhone());
        leadResponseDto.setLeadGender(leadResponseDto.getLeadGender());
        return leadResponseDto;
    }

    private LeadData convertDtoToModel(LeadRequestDto leadRequestDto) {
        LeadData leadData = new LeadData();
        leadData.setLeadEmail(leadRequestDto.getEmail());
        leadData.setLeadName(leadRequestDto.getName());
        leadData.setLeadPhone(leadRequestDto.getPhone());
        leadData.setPhoneCountryCode(leadRequestDto.getPhoneCountryCode());
        leadData.setWhatsappCountryCode(leadRequestDto.getWhatsappCountryCode());
        leadData.setLeadWatsApp(leadRequestDto.getWhatsapp());
        leadData.setGender(leadRequestDto.getGender());
        leadData.setAddress(addressDtoToModel(leadRequestDto.getAddress()));
        leadData.setTest(leadRequestDto.isTest());
        return leadData;
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

}
