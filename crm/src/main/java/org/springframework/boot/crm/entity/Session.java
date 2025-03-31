package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

@Entity
@Table(name = "session")
@Data
@NoArgsConstructor
@AllArgsConstructor // Add table annotation
public class Session {

    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private int userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false)
    private SessionType sessionType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.ACTIVE;



    @Column(name = "expiry_time")
    private LocalDateTime expiryTime;

    public enum SessionType {
        WHATSAPP, BROWSER_AGENT
    }

    public enum SessionStatus {
        ACTIVE, PAUSED, COMPLETED, EXPIRED, FAILED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }



}
