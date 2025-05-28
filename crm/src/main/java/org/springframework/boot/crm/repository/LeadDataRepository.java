package org.springframework.boot.crm.repository;

import jakarta.persistence.QueryHint;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;

@Repository
public interface LeadDataRepository extends JpaRepository<LeadData, Integer> {

    Optional<LeadData> findByLeadId(Integer leadId);

}
