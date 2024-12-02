package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;


@Data
@Entity(name = "business_lead")
public class BusinessLead {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="data_id")
    private int dataId;

    @Column(name="lead_id")
    private int leadId;

    @Column(name="business_id")
    private int businessId;

    @Embedded
    private SanitaryColumn sanitaryColumn;

}
