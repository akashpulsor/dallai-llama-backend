package com.dalai.llama.agent.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "call_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CallSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", unique = true, nullable = false)
    private String callId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "agent_session_id")
    private Long agentSessionId;

    @Column(name = "customer_number", length = 50)
    private String customerNumber;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "did_number", length = 50)
    private String didNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Direction direction;

    @Column(name = "queue_id", length = 128)
    private String queueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private CallStatus status = CallStatus.RINGING;

    @Column(name = "call_state", length = 50)
    private String callState;

    @Column(name = "ring_start_time", nullable = false)
    @Builder.Default
    private OffsetDateTime ringStartTime = OffsetDateTime.now();

    @Column(name = "answer_time")
    private OffsetDateTime answerTime;

    @Column(name = "hold_start_time")
    private OffsetDateTime holdStartTime;

    @Column(name = "end_time")
    private OffsetDateTime endTime;

    @Column(name = "ring_duration")
    private Integer ringDuration;

    @Column(name = "talk_duration")
    private Integer talkDuration;

    @Column(name = "hold_duration")
    private Integer holdDuration;

    @Column(name = "total_duration")
    private Integer totalDuration;

    @Column(name = "after_call_work_duration")
    private Integer afterCallWorkDuration;

    @Column(name = "rtp_stream_id")
    private String rtpStreamId;

    @Column(name = "media_server_id")
    private String mediaServerId;

    @Column(name = "codec", length = 50)
    private String codec;

    @Column(name = "webrtc_session_id")
    private String webrtcSessionId;

    @Column(name = "ice_connection_state", length = 50)
    private String iceConnectionState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public enum Direction {
        INBOUND, OUTBOUND
    }

    public enum CallStatus {
        RINGING, ANSWERED, ON_HOLD, TRANSFERRED, ENDED, FAILED
    }

    public void answer() {
        this.answerTime = OffsetDateTime.now();
        this.status = CallStatus.ANSWERED;
        this.ringDuration = (int) java.time.Duration.between(ringStartTime, answerTime).getSeconds();
    }

    public void end() {
        this.endTime = OffsetDateTime.now();
        this.status = CallStatus.ENDED;
        if (answerTime != null) {
            this.talkDuration = (int) java.time.Duration.between(answerTime, endTime).getSeconds();
        }
        this.totalDuration = (int) java.time.Duration.between(ringStartTime, endTime).getSeconds();
    }
}
