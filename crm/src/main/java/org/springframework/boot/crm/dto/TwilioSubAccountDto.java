package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class TwilioSubAccountDto {
    private String friendlyName;
    private String sid;
    private String authToken;
    private String status;
    private String dateCreated;
    private int phoneId;
    private int businessId;
    private boolean phoneGenerated;
}
