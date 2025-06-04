package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.DalaiLlamaLeads;
import org.springframework.boot.crm.repository.DalaiLLamaLeadDataRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DalaiLLamaLeadDataService {

    private final DalaiLLamaLeadDataRepository dalaiLLamaLeadDataRepository;

    public DalaiLLamaLeadDataService(DalaiLLamaLeadDataRepository dalaiLLamaLeadDataRepository) {
        this.dalaiLLamaLeadDataRepository = dalaiLLamaLeadDataRepository;
    }

    public DalaiLlamaLeads save(DalaiLlamaLeads dalaiLlamaLeads) {
        if (dalaiLlamaLeads.getUniqueId() != null && !dalaiLlamaLeads.getUniqueId().isEmpty()
                && dalaiLlamaLeads.getSource() != null && dalaiLlamaLeads.getCampaign() != null) {
            Optional<DalaiLlamaLeads> existingOpt = dalaiLLamaLeadDataRepository.findByUniqueIdAndSourceAndCampaign(
                    dalaiLlamaLeads.getUniqueId(),
                    dalaiLlamaLeads.getSource(),
                    dalaiLlamaLeads.getCampaign()
            );
            if (existingOpt.isPresent()) {
                DalaiLlamaLeads existing = existingOpt.get();
                if (dalaiLlamaLeads.getName() != null) existing.setName(dalaiLlamaLeads.getName());
                if (dalaiLlamaLeads.getEmail() != null) existing.setEmail(dalaiLlamaLeads.getEmail());
                if (dalaiLlamaLeads.getPhone() != null) existing.setPhone(dalaiLlamaLeads.getPhone());
                if (dalaiLlamaLeads.getCountryCode() != null) existing.setCountryCode(dalaiLlamaLeads.getCountryCode());
                if (dalaiLlamaLeads.getCompanySize() != null) existing.setCompanySize(dalaiLlamaLeads.getCompanySize());
                if (dalaiLlamaLeads.getCountryCallingCode() != null) existing.setCountryCallingCode(dalaiLlamaLeads.getCountryCallingCode());
                if (dalaiLlamaLeads.getActivityDescription() != null) existing.setActivityDescription(dalaiLlamaLeads.getActivityDescription());

                return dalaiLLamaLeadDataRepository.save(existing);
            }
        }
        return this.dalaiLLamaLeadDataRepository.save(dalaiLlamaLeads);
    }

    public Page<DalaiLlamaLeads> getAllLeadsPaginated(Pageable pageable) {
        return this.dalaiLLamaLeadDataRepository.findAll(pageable);
    }

}
