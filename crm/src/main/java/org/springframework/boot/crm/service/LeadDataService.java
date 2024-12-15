package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessLead;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.boot.crm.exceptions.LeadNotFoundException;
import org.springframework.boot.crm.repository.BusinessLeadRepository;
import org.springframework.boot.crm.repository.LeadDataRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class LeadDataService {

    private LeadDataRepository leadDataRepository;

    private BusinessLeadRepository businessLeadRepository;

    public LeadDataService(LeadDataRepository leadDataRepository,
                           BusinessLeadRepository businessLeadRepository) {
        this.leadDataRepository = leadDataRepository;
        this.businessLeadRepository = businessLeadRepository;
    }

    public LeadData save(LeadData leadData) {
        return leadDataRepository.save(leadData);
    }

    public LeadData findById(int leadId) {
        return leadDataRepository.findByLeadId(leadId);
    }

    public List<LeadData> getLeadByBusinessId(int businessId) {
        return this.businessLeadRepository.findLeadsByBusinessId(businessId);
    }

    @Transactional(readOnly = true)
    public Page<LeadData> getLeadsByBusinessIdPaginated(int businessId, Pageable pageable) {
        return this.businessLeadRepository.findLeadsByBusinessId(businessId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<LeadData> getTestLeadsByBusinessIdPaginated(int businessId, Pageable pageable) {
        return this.businessLeadRepository.findTestLeadsByBusinessId(businessId, pageable);
    }

    public void addBusinessLead(int leadId, int businessId) {
        if (this.businessLeadRepository.findByLeadIdAndBusinessId(leadId, businessId).isPresent()) {
            throw new IllegalStateException("Mapping already exists between lead " + leadId + " and business " + businessId);
        }
        if (!this.businessLeadRepository.findByLeadIdAndBusinessId(leadId, businessId).isPresent()) {
            BusinessLead mapping = new BusinessLead();
            mapping.setLeadId(leadId);
            mapping.setBusinessId(businessId);
            this.businessLeadRepository.save(mapping);
        }
    }

    public LeadData getBusinessLead(int leadId, int businessId) {
        return this.businessLeadRepository.
                findByLeadIdAndBusinessId(leadId, businessId).
                orElseThrow(()-> new LeadNotFoundException("Lead not found"));
    }


    public List<LeadData> getLeadDataByStream( Integer businessId,  Set<Integer> leadIds) {
        return this.businessLeadRepository.findExistingLeadIds(businessId, leadIds);
    }

    public Stream<LeadData> getLeadDataByStream(int businessId) {
        return this.businessLeadRepository.findByLeadIdAndBusinessIdStream(businessId);
    }




}
