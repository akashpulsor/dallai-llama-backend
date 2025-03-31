package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.SessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SessionEventRepository extends JpaRepository<SessionEvent, String> {
    List<SessionEvent> findBySessionId(String sessionId);
    List<SessionEvent> findBySessionIdAndEventType(String sessionId, String eventType);
    List<SessionEvent> findByEventType(String eventType);
    List<SessionEvent> findByTimestampBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<SessionEvent> findBySessionIdOrderByTimestampDesc(String sessionId);
}
