package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class RegisterRequestDto {
    @Email
    private String email;
    private String name;
    @NotBlank
    private String mobile;
    private String businessName;
    private String countryCallingCode;
    private String countryCode;
    private String basicActivityDescription;
    private int companySize;
    private String password;


    /*
    * { "name":name, "businessName":companyName, "email": email,"password":password,"mobile": phone,
          "countryCallingCode": countryCallingCode,
          * "countryCode": countryCode, "companySize":companySize,
          * "basicActivityDescription": basicActivityDescription}
    *
    *
    *
    * */

    Set<String> roles = new HashSet<>();
}
