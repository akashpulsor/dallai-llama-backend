package org.springframework.boot.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "call_status")
public class CallStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "status_id")
    private int statusId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "call_log_id", nullable = false)
    private CallLog callLog;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Override
    public String toString() {
        return "CallStatus{" +
                "callLogId=" + statusId +
                "status=" + status +
                '}';
    }
}
