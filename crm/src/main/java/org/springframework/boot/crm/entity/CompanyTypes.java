package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "company_types")
public class CompanyTypes {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="company_type_id")
    private int companyTypeId;

    @Column(name="company_type_name")
    private String companyTypeName;

    @Column(name="business_classification_id")
    private int businessClassificationId;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
