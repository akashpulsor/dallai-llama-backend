package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TwilioDataRepository extends JpaRepository<TwilioData, Integer> {

    List<TwilioData> findByBusinessId(Integer businessId);
}
