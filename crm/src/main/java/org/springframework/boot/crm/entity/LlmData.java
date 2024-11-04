package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "llm_data")
public class LlmData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="llm_id")
    private int llmId;

    @Column(name="business_id")
    private int businessId;

    @Column(name="api_key")
    private String apiKey;

    @Column(name="vendor_name")
    private String vendorName;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
