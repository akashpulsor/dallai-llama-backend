package org.springframework.boot.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class Address {

    @Column(name="street")
    private String street;

    @Column(name="apartment")
    private String apartment;

    @Column(name="city")
    private String city;

    @Column(name="state")
    private String state;

    @Column(name="zip_code")
    private String zipCode;

    @Column(name="country_code")
    private String countryCode;

    @Column(name="formatted_address")
    private String formattedAddress;

}
