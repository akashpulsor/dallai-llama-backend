package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.data.domain.Page;

import java.util.List;

public interface LeadManager {

    LeadResponseDto addLead(LeadRequestDto leadRequestDto);

    List<LeadResponseDto> getLead(int businessId);


    Page<LeadResponseDto> getLeadsByBusinessIdPaginated(int businessId,int page,int size,String sortBy, boolean test);

}
