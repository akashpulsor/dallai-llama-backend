package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "browser_actions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrowserAction {
    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    private String id;

    @ManyToOne
    @JoinColumn(name = "session_id", insertable = false, updatable = false)
    private Session session;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String actionData;

    @ManyToOne
    @JoinColumn(name = "intent_id")
    private Intent intent;



    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionStatus status = ActionStatus.PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @ManyToOne
    @JoinColumn(name = "session_id")
    private BrowserSession browserSession;  // Add this field

    public enum ActionStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, RETRYING
    }


    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        completedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        completedAt = LocalDateTime.now();
    }

}
