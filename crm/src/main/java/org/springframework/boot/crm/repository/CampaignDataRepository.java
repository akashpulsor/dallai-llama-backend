package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignDataRepository extends JpaRepository<CampaignData, Integer> {

    Optional<CampaignData> findByCampaignIdAndBusinessId(Integer campaignId, Integer businessId);

    CampaignData save(CampaignData campaignData);

    List<CampaignData> findByBusinessId(Integer businessId);

/*
    @Query("SELECT COUNT(DISTINCT cd.campaignId) FROM CampaignData cd WHERE cd.businessId = :businessId AND cd.createdAt BETWEEN :startDate AND :endDate")
    int countUniqueCampaignIdsByBusinessIdAndCreatedAtBetween(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query(value = "SELECT COUNT(DISTINCT cd.campaignId) FROM campaign_data cd WHERE cd.businessId = :businessId AND cd.createdAt >= :startDate", nativeQuery = true)
    int countUniqueCampaignIdsByBusinessIdAndCreatedAtAfter(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate
    );

    default int getTotalUniqueCampaignCount(Integer businessId, LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate != null && endDate != null) {
            return countUniqueCampaignIdsByBusinessIdAndCreatedAtBetween(businessId, startDate, endDate);
        } else {
            LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
            return countUniqueCampaignIdsByBusinessIdAndCreatedAtAfter(businessId, sixtyDaysAgo);
        }
    }

    default int getTotalUniqueCampaignCount(Integer businessId) {
        LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
        return countUniqueCampaignIdsByBusinessIdAndCreatedAtAfter(businessId, sixtyDaysAgo);
    }
*/

}
