package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Data
@Entity(name = "payment_data")
public class PaymentData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private int paymentId;

    @Column(name = "call_id")
    private int callId;

    @Column(name = "start_time")
    private Date startTime;

    @Column(name = "end_time")
    private Date endTime;

    @Column(name = "input_token")
    private int inputToken;

    @Column(name = "output_token")
    private int outputToken;

    @Column(name = "total_token")
    private int totalToken;

    @Column(name = "call_time")
    private int callTime;
}
