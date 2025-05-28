package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.LeadRequestDto;
import org.springframework.boot.crm.dto.LeadResponseDto;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public interface LeadManager {

    LeadResponseDto addLead(LeadRequestDto leadRequestDto);

    List<LeadResponseDto> getLead(int businessId);

    LeadResponseDto getLead(int businessId, int leadId);

    LeadData getLeadData(int businessId, int leadId);

    LeadData getLeadData( int leadId);

    Page<LeadResponseDto> getLeadsByBusinessIdPaginated(int businessId,int page,int size,String sortBy, boolean test);

    Stream<LeadData> getLeadDataByStream(int businessId);

    List<LeadData> getLeadDataByList( Integer businessId,  Set<Integer> leadIds);

    long totalLeads(int businessId, LocalDate startDate, LocalDate endDate);
}
