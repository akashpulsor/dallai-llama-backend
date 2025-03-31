package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SessionRepository  extends JpaRepository<Session, Long> {
    List<Session> findByUserId(Long userId);
    List<Session> findBySessionType(Session.SessionType sessionType);
    List<Session> findByStatus(Session.SessionStatus status);
    List<Session> findByExpiryTimeBefore(LocalDateTime dateTime);
    List<Session> findByUserIdAndStatus(Long userId, Session.SessionStatus status);
}
