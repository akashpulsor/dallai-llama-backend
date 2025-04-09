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
import java.util.stream.Stream;

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;

@Repository
public interface LeadDataRepository extends JpaRepository<LeadData, Integer> {

    LeadData findByLeadId(Integer leadId);
/*
    @Query("SELECT COUNT(ld) FROM LeadData ld WHERE ld.businessId = :businessId AND ld.sanitaryColumn.createdAt BETWEEN :startDate AND :endDate")
    long countLeadsByBusinessIdAndCreatedAtBetween(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT COUNT(ld) FROM LeadData ld WHERE ld.businessId = :businessId AND ld.sanitaryColumn.createdAt >= :startDate")
    long countLeadsByBusinessIdAndCreatedAtAfter(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate
    );

    default long totalLead(Integer businessId, LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate != null && endDate != null) {
            return countLeadsByBusinessIdAndCreatedAtBetween(businessId, startDate, endDate);
        } else {
            LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
            return countLeadsByBusinessIdAndCreatedAtAfter(businessId, sixtyDaysAgo);
        }
    }

    default long totalLead(Integer businessId) {
        LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
        return countLeadsByBusinessIdAndCreatedAtAfter(businessId, sixtyDaysAgo);
    }
*/
}
