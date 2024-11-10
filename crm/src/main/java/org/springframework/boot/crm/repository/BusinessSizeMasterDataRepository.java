package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BusinessSizeMasterDataRepository extends JpaRepository<BusinessSizeMasterData, Integer> {




}
