package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.DalaiLlamaLeads;
import org.springframework.boot.crm.repository.DalaiLLamaLeadDataRepository;
import org.springframework.boot.crm.repository.PaymentDataRepository;
import org.springframework.stereotype.Service;

@Service
public class DalaiLLamaLeadDataService {


    private final DalaiLLamaLeadDataRepository dalaiLLamaLeadDataRepository;

    public DalaiLLamaLeadDataService(DalaiLLamaLeadDataRepository dalaiLLamaLeadDataRepository) {
        this.dalaiLLamaLeadDataRepository = dalaiLLamaLeadDataRepository;
    }


    public DalaiLlamaLeads save(DalaiLlamaLeads dalaiLlamaLeads) {
        return this.dalaiLLamaLeadDataRepository.save(dalaiLlamaLeads);
    }
}
