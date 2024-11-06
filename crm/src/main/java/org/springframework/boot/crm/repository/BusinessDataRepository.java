package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessDataRepository extends JpaRepository<BusinessData, Integer> {




    Optional<BusinessData> findByEmail(String email);

    Optional<BusinessData> findByMobile(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByMobile(String phoneNumber);

}
