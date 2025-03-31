package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PortalDTO {
    private int portalId;
    private String portalName;
    private String baseUrl;
    private String PortalDescription;

}
