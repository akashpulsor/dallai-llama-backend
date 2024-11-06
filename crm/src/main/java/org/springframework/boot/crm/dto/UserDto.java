package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class UserDto {

    private int businessId;

    private String name;

    private String mobileNumber;

    private String email;
}
