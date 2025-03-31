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

    @Column(name="friendly_name")
    private String friendlyName;

    @Column(name="active")
    private boolean active;

    @Column(name="logo_image")
    private String logoImage;

    @Column(name="model_name")
    private String modelName;

    @Column(name="multi_modal")
    private boolean multiModal = false;

    @Column(name="model_url")
    private String modelUrl;

    //Add model name,
    //Model url

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
