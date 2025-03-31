package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BrowserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BrowserSessionRepository extends JpaRepository<BrowserSession, String> {
    List<BrowserSession> findByPortalId(Long portalId);
    Optional<BrowserSession> findBySessionId(String sessionId);
    List<BrowserSession> findByCampaignId(Long campaignId);
    List<BrowserSession> findByLastActionTimestampBefore(LocalDateTime dateTime);
}
