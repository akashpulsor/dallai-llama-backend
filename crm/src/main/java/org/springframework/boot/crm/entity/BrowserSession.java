package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "browser_session")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrowserSession {
    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    @Column(name = "session_id")
    private String sessionId;


    @Column(name = "browser_run_id")
    private String browserRunId;

    @Column(name = "portal_id", nullable = false)
    private int portalId;

    @Column(name = "current_url", length = 1024)
    private String currentUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Session.SessionStatus status = Session.SessionStatus.ACTIVE;



    @Column(name = "last_action_timestamp")
    private LocalDateTime lastActionTimestamp;


    @Column(name = "campaign_id")
    private int campaignId;

    @OneToOne
    @JoinColumn(name = "sessionId", referencedColumnName = "id")
    @MapsId
    private Session session;


}
