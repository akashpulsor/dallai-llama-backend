package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "lead_data")
public class LeadData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="lead_id")
    private int leadId;

    @Column(name="gender")
    private String gender;

    @Column(name="lead_email")
    private String leadEmail;

    @Column(name="lead_name")
    private String leadName;

    @Column(name="lead_phone")
    private String leadPhone;

    @Column(name="phone_country_code")
    private String phoneCountryCode;


    @Column(name="lead_whatsapp")
    private String leadWatsApp;

    @Column(name="whatsapp_country_code")
    private String whatsappCountryCode;

    @Column(name="test")
    private Boolean test;

    @Embedded
    private Address address;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
