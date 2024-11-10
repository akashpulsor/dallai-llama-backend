package org.springframework.boot.crm.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
import org.springframework.boot.crm.repository.BusinessSizeMasterDataRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Data
@Slf4j
@Service
public class MasterDataService {

    private final BusinessSizeMasterDataRepository businessSizeMasterDataRepository;

    public MasterDataService(BusinessSizeMasterDataRepository businessSizeMasterDataRepository) {
        this.businessSizeMasterDataRepository = businessSizeMasterDataRepository;
    }



    public BusinessSizeMasterData addBusinessSize(BusinessSizeMasterData businessSizeMasterData) {
        return this.businessSizeMasterDataRepository.save(businessSizeMasterData);
    }

    public List<BusinessSizeMasterData> getAllBusinessSize() {
        return this.businessSizeMasterDataRepository.findAll();
    }


}
