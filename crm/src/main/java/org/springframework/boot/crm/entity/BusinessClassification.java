package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;


@Data
@Entity(name = "business_classification")
public class BusinessClassification {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="id")
    private int id;

    @Column(name="criteria")
    private String criteria;

    @Column(name="country_id")
    private int countryId;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
