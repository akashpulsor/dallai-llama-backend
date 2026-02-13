package com.dalai.llama.product.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Product app configuration")
public class ProductAppResponse {

    @Schema(description = "App ID")
    private UUID id;

    @Schema(description = "App type", example = "CONTACT_CENTER")
    private String appType;

    @Schema(description = "Subdomain for the app", example = "app")
    private String subdomain;

    @Schema(description = "Display name", example = "Contact Center")
    private String displayName;

    @Schema(description = "Keycloak client suffix (null = use primary)", example = "ivr")
    private String keycloakClientSuffix;

    @Schema(description = "Frontend Docker image", example = "dalaillama/agent-ui:latest")
    private String frontendImage;

    @Schema(description = "Frontend port", example = "80")
    private int frontendPort;

    @Schema(description = "Required roles (comma-separated)", example = "AGENT,SUPERVISOR,TENANT_ADMIN")
    private String requiredRoles;

    @Schema(description = "App icon", example = "📞")
    private String icon;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Display order")
    private int displayOrder;
}