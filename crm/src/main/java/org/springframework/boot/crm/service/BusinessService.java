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

    public BusinessData getBusinessDataByEmail(String email) {
        log.info("Fetching Data about Email : {}", email);
        return this.businessDataRepository.findByEmail(email).
                orElseThrow(() -> new BusinessNotFoundException("Business not found!"));
    }

    public BusinessData getBusinessDataByMobile(String mobile) {
        log.info("Fetching Data about Mobile : {}", mobile);
        return this.businessDataRepository.findByMobile(mobile).
                orElseThrow(() -> new BusinessNotFoundException("Business not found!"));
    }

    public boolean checkEmailExists(String email) {
        log.info("Checking if Email exists : {}", email);
        return this.businessDataRepository.existsByEmail(email);
    }

    public boolean checkPhoneExists(String mobile) {
        log.info("Checking if Mobile exists : {}", mobile);
        return this.businessDataRepository.existsByMobile(mobile);
    }
}
