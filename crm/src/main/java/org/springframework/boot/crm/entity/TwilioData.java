package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "twilio_data")
public class TwilioData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="twilio_id")
    private int twilioId;

    @Column(name="business_id")
    private int businessId;

    @Column(name="account_auth_token")
    private String accountAuthToken;

    @Column(name="account_sid")
    private String accountSid;

    @Column(name="friendly_name")
    private String friendlyName;

    @Column(name="business_number")
    private String businessNumber;

    @Column(name="call_secret")
    private String callSecret;

    @Column(name="status")
    private String status;

    @Column
    private boolean active;
    @Embedded
    private SanitaryColumn sanitaryColumn;


}
