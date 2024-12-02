package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.BusinessDataIndia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessDataIndiaRepository extends JpaRepository<BusinessDataIndia, Integer> {

    Optional<BusinessDataIndia> findByParentBusinessId(Integer parentBusinessId);
}
