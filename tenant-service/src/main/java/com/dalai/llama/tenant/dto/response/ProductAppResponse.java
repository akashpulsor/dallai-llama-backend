package com.dalai.llama.tenant.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record ProductAppResponse(

        @Schema(description = "App ID")
        UUID id,

        @Schema(description = "App type", example = "CONTACT_CENTER")
        String appType,

        @Schema(description = "Subdomain for the app", example = "app")
        String subdomain,

        @Schema(description = "Display name", example = "Contact Center")
        String displayName,

        @Schema(description = "Keycloak client suffix (null = use primary)", example = "ivr")
        String keycloakClientSuffix,

        @Schema(description = "Frontend Docker image", example = "dalaillama/agent-ui:latest")
        String frontendImage,

        @Schema(description = "Frontend port", example = "80")
        int frontendPort,

        @Schema(description = "Required roles (comma-separated)", example = "AGENT,SUPERVISOR,TENANT_ADMIN")
        String requiredRoles,

        @Schema(description = "App icon", example = "📞")
        String icon,

        @Schema(description = "Description")
        String description,

        @Schema(description = "App is enabled or not")
        boolean enabled,

        @Schema(description = "Display order")
        int displayOrder

) {}
