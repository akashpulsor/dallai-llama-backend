package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PortalConfigurationDTO {
    private String portalName;
    private String baseUrl;
    private String portalDescription;
    private int businessId;
    private String userName;
    private String password;
    private String intent;
    private String steps;
}
