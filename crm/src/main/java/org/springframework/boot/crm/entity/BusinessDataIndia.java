package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "business_data_india")
public class BusinessDataIndia {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="business_id")
    private int businessId;

    @Column(name = "parent_business_id")
    private int parentBusinessId;

    @Column(name="udyam_registration_number")
    private String udyamRegistrationNumber;

    @Column(name="pan")
    private String pan;

    @Column(name="adhaar_number")
    private String adhaarNumber;

    @Column(name="country_id")
    private int countryId;

    @Column(name="company_type_id")
    private int companyTypeId;

    @Column(name="active")
    private boolean active;

    @Column(name="gst_in")
    private String gstIn;

    @Embedded
    private Address address;

    @Embedded
    private BankDetails bankDetails;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
