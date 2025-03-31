package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.crm.entity.Intent;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class PortalConfigurationResponse {

    private Integer portalId;

    private String portalName;

    private String portalDescription;

    private String baseUrl;

    private int businessId;

    List<IntentDTO> intents = new ArrayList<>();

}
