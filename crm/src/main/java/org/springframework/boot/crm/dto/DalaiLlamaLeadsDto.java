package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DalaiLlamaLeadsDto {
    @JsonProperty("name")
    private String name;

    @JsonProperty("email")
    private String email;

    @JsonProperty("countryCode")
    private String countryCode;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    @JsonProperty("companySize")
    private String companySize;

    @JsonProperty("description")
    private String description;

    @JsonProperty("countryCallingCode")
    private String countryCallingCode;

    @JsonProperty("uniqueId")
    private String uniqueId;

    @JsonProperty("source")
    private String source;

    @JsonProperty("campaign")
    private String campaign;

}
