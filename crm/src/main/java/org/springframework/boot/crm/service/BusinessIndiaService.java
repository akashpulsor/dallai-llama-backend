package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessDataIndia;
import org.springframework.boot.crm.repository.BusinessDataIndiaRepository;
import org.springframework.boot.crm.repository.BusinessDataRepository;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BusinessIndiaService {

    private final BusinessDataIndiaRepository businessDataIndiaRepository;

    public BusinessIndiaService(BusinessDataIndiaRepository businessDataIndiaRepository){
        this.businessDataIndiaRepository = businessDataIndiaRepository;
    }



    public BusinessDataIndia addBusinessData(BusinessDataIndia businessDataIndia) {
        return businessDataIndiaRepository.save(businessDataIndia);
    }

    public BusinessDataIndia getBusinessData(int businessId) {
        return businessDataIndiaRepository.findByParentBusinessId(businessId).orElse( new BusinessDataIndia());
    }
}
