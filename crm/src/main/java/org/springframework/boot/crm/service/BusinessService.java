package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.exceptions.BusinessNotFoundException;
import org.springframework.boot.crm.repository.BusinessDataRepository;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BusinessService {

    private final BusinessDataRepository businessDataRepository;
    public BusinessService(BusinessDataRepository businessDataRepository){
        this.businessDataRepository = businessDataRepository;
    }

    public BusinessData getBusinessDataById(int businessId) {
        log.info("Fetching Data about business Id");
        return this.businessDataRepository.findById(businessId).
                orElseThrow(() -> new BusinessNotFoundException("Business not found!"));
    }

    public BusinessData addBusiness(BusinessData businessData) {
        log.info("Adding business Data - {}", businessData);
        return this.businessDataRepository.save(businessData);
    }


}
