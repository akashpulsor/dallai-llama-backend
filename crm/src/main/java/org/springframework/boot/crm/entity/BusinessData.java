package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "business_data")
public class BusinessData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="business_id")
    private int businessId;

    @Column(name="email")
    private String email;

    @Column(name="mobile")
    private String mobile;

    @Column(name="whatsapp_number")
    private String whatsAppNumber;

    @Column(name="business_name")
    private String businessName;

    @Column(name="country_id")
    private int countryId;

    @Column(name="number_of_employee")
    private int numberOfEmployee;

    @Column(name="basic_activity_description")
    private String basicActivityDescription;

}
