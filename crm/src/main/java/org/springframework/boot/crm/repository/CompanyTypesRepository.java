package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CompanyTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyTypesRepository extends JpaRepository<CompanyTypes, Integer> {
}
