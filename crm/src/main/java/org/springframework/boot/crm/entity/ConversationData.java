package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Data
@Entity(name = "conversation_data")
public class ConversationData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private int paymentId;

    @Column(name = "call_id")
    private int callId;

    @Column(name="conversation_data")
    private String conversation;
}
