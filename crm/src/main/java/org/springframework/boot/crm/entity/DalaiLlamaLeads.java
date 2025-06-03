package org.springframework.boot.crm.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "dalai_llama_lead_data")
public class DalaiLlamaLeads {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="lead_id")
    private int leadId;

    @Column(name="email")
    private String email;

    @Column(name="name")
    private String name;

    @Column(name="phone")
    private String phone;

    @Column(name="country_code")
    private String countryCode;

    @Column(name="country_calling_code")
    private String countryCallingCode;

    @Column(name="company_size")
    private String companySize;

    @Column(name="uniqueId")
    private String uniqueId;

    @Column(name="source")
    private String source;

    @Column(name="campaign")
    private String campaign;

    @Column(name="activity_description")
    private String activityDescription;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
