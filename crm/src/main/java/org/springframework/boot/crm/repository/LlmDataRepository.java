package org.springframework.boot.crm.repository;


import org.springframework.boot.crm.entity.LlmData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmDataRepository extends JpaRepository<LlmData, Integer> {

    Optional<LlmData> findByBusinessId(Integer businessId);
}
