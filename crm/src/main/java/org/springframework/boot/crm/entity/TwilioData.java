package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "twilio_data")
public class TwilioData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="phone_id")
    private int phoneId;

    @Column(name="business_id")
    private int businessId;

    @Column(name="vendor_name")
    private String vendorName;

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

    @Column(name="logo_image")
    private String logoImage;

    @Column(name="status")
    private String status;

    @Column
    private boolean active;
    @Embedded
    private SanitaryColumn sanitaryColumn;


}
