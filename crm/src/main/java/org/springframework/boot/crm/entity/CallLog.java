package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity(name = "call_log")
public class CallLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_log_id")
    private int callLogId;

    @Column(name = "call_type")
    private String callType;

    @Column(name = "campaign_run_id")
    private int campaignRunId;

    @Column(name = "lead_id")
    private int leadId;

    @Column(name = "call_sid")
    private String callSid;

    @Column(name = "from_number")
    private String fromNumber;

    @Column(name = "to_number")
    private String toNumber;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @OneToMany(mappedBy = "callLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("timestamp ASC")
    private List<CallStatus> statusHistory = new ArrayList<>();


}
