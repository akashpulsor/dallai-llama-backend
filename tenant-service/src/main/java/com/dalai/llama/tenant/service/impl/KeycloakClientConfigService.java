package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Keycloak Client Configuration Service
 *
 * Creates OIDC clients for tenant frontend apps:
 * - One base client per tenant (dalaillama-{tenant})
 * - Additional clients per app if needed (dalaillama-{tenant}-{app})
 *
 * Client configuration:
 * - Public client (PKCE for SPAs)
 * - Redirect URIs for app domains
 * - Role mappings for authorization
 *
 * When user clicks app panel URL:
 * 1. Frontend redirects to Keycloak login
 * 2. Keycloak authenticates in tenant realm
 * 3. Returns JWT with roles
 * 4. Frontend validates and authorizes based on requiredRoles
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakClientConfigService {

    private final Keycloak keycloakAdmin;
    private final ObjectMapper objectMapper;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    /**
     * Create Keycloak clients for tenant apps
     */
    public void createClientsForTenant(TenantApp app) {
        String tenantSlug = app.getNamespace();
        String realmName = tenantSlug;

        log.info("Creating Keycloak clients for tenant {} in realm {}", tenantSlug, realmName);

        try {
            RealmResource realm = keycloakAdmin.realm(realmName);

            // Parse app panels
            List<AppPanel> apps = parseAppPanels(app.getAppPanels());

            // Track created client IDs to avoid duplicates
            Set<String> createdClients = new HashSet<>();

            for (AppPanel panel : apps) {
                String clientId = panel.keycloakClientId;

                if (createdClients.contains(clientId)) {
                    continue; // Skip duplicate
                }

                // Check if client exists
                List<ClientRepresentation> existing = realm.clients().findByClientId(clientId);
                if (!existing.isEmpty()) {
                    log.debug("Client {} already exists, updating", clientId);
                    updateClient(realm, existing.get(0), panel, tenantSlug);
                } else {
                    createClient(realm, clientId, panel, tenantSlug);
                }

                createdClients.add(clientId);
            }

            log.info("Created {} Keycloak clients for tenant {}", createdClients.size(), tenantSlug);

        } catch (Exception e) {
            log.error("Failed to create Keycloak clients for {}: {}", tenantSlug, e.getMessage());
            throw new RuntimeException("Keycloak client creation failed", e);
        }
    }

    private void createClient(RealmResource realm, String clientId, AppPanel panel, String tenantSlug) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setName(panel.displayName);
        client.setDescription("Client for " + panel.appType + " - " + tenantSlug);
        client.setEnabled(true);

        // Public client for SPA
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(false);
        client.setStandardFlowEnabled(true);

        // PKCE
        client.setAttributes(Map.of(
                "pkce.code.challenge.method", "S256",
                "post.logout.redirect.uris", panel.url + "/*"
        ));

        // Redirect URIs
        String appUrl = panel.url;
        client.setRedirectUris(List.of(
                appUrl + "/*",
                appUrl + "/callback",
                appUrl + "/silent-check-sso.html"
        ));

        // Web origins for CORS
        client.setWebOrigins(List.of(appUrl, "*"));

        // Root URL
        client.setRootUrl(appUrl);
        client.setBaseUrl(appUrl);

        // Protocol mappers for custom claims
        client.setProtocolMappers(createProtocolMappers(tenantSlug, panel));

        realm.clients().create(client);
        log.info("Created Keycloak client: {}", clientId);
    }

    private void updateClient(RealmResource realm, ClientRepresentation existing,
                              AppPanel panel, String tenantSlug) {
        String appUrl = panel.url;

        // Update redirect URIs
        List<String> redirectUris = new ArrayList<>(existing.getRedirectUris());
        if (!redirectUris.contains(appUrl + "/*")) {
            redirectUris.add(appUrl + "/*");
            redirectUris.add(appUrl + "/callback");
        }
        existing.setRedirectUris(redirectUris);

        // Update web origins
        List<String> webOrigins = new ArrayList<>(existing.getWebOrigins());
        if (!webOrigins.contains(appUrl)) {
            webOrigins.add(appUrl);
        }
        existing.setWebOrigins(webOrigins);

        realm.clients().get(existing.getId()).update(existing);
        log.debug("Updated Keycloak client: {}", existing.getClientId());
    }

    private List<ProtocolMapperRepresentation> createProtocolMappers(String tenantSlug, AppPanel panel) {
        List<ProtocolMapperRepresentation> mappers = new ArrayList<>();

        // Tenant ID mapper
        ProtocolMapperRepresentation tenantMapper = new ProtocolMapperRepresentation();
        tenantMapper.setName("tenant_id");
        tenantMapper.setProtocol("openid-connect");
        tenantMapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        tenantMapper.setConfig(Map.of(
                "claim.name", "tenant_id",
                "claim.value", tenantSlug,
                "jsonType.label", "String",
                "id.token.claim", "true",
                "access.token.claim", "true",
                "userinfo.token.claim", "true"
        ));
        mappers.add(tenantMapper);

        // App type mapper
        ProtocolMapperRepresentation appMapper = new ProtocolMapperRepresentation();
        appMapper.setName("app_type");
        appMapper.setProtocol("openid-connect");
        appMapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        appMapper.setConfig(Map.of(
                "claim.name", "app_type",
                "claim.value", panel.appType,
                "jsonType.label", "String",
                "id.token.claim", "true",
                "access.token.claim", "true"
        ));
        mappers.add(appMapper);

        // Realm roles mapper
        ProtocolMapperRepresentation rolesMapper = new ProtocolMapperRepresentation();
        rolesMapper.setName("realm_roles");
        rolesMapper.setProtocol("openid-connect");
        rolesMapper.setProtocolMapper("oidc-usermodel-realm-role-mapper");
        rolesMapper.setConfig(Map.of(
                "claim.name", "roles",
                "jsonType.label", "String",
                "multivalued", "true",
                "id.token.claim", "true",
                "access.token.claim", "true",
                "userinfo.token.claim", "true"
        ));
        mappers.add(rolesMapper);

        return mappers;
    }

    /**
     * Create default roles in tenant realm
     */
    public void createDefaultRoles(String realmName) {
        try {
            RealmResource realm = keycloakAdmin.realm(realmName);

            String[] roles = {
                    "AGENT", "SUPERVISOR", "TENANT_ADMIN", "REPORTING_VIEWER",
                    "DIALER_AGENT", "RECEPTIONIST", "API_USER"
            };

            for (String roleName : roles) {
                try {
                    if (realm.roles().get(roleName).toRepresentation() == null) {
                        org.keycloak.representations.idm.RoleRepresentation role =
                                new org.keycloak.representations.idm.RoleRepresentation();
                        role.setName(roleName);
                        role.setDescription("Dalai LLAMA " + roleName + " role");
                        realm.roles().create(role);
                        log.debug("Created role: {} in realm {}", roleName, realmName);
                    }
                } catch (Exception e) {
                    // Role might already exist
                }
            }

            log.info("Ensured default roles exist in realm {}", realmName);

        } catch (Exception e) {
            log.error("Failed to create roles in {}: {}", realmName, e.getMessage());
        }
    }

    /**
     * Get frontend configuration for app
     */
    public FrontendConfig getFrontendConfig(String tenantSlug, String appType) {
        String clientId = "dalaillama-" + tenantSlug;
        if (!"CONTACT_CENTER".equals(appType) && !"IVR_BUILDER".equals(appType)) {
            clientId = "dalaillama-" + tenantSlug + "-" + appType.toLowerCase();
        }

        return new FrontendConfig(
                "https://auth." + baseDomain,
                tenantSlug,
                clientId,
                "https://auth." + baseDomain + "/realms/" + tenantSlug
        );
    }

    public record FrontendConfig(
            String keycloakUrl,
            String realm,
            String clientId,
            String issuer
    ) {}

    private List<AppPanel> parseAppPanels(String appPanelsJson) {
        if (appPanelsJson == null || appPanelsJson.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(appPanelsJson, AppPanel[].class));
        } catch (Exception e) {
            log.error("Failed to parse app panels: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteClientsForTenant(String realmName) {
        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            List<ClientRepresentation> clients = realm.clients().findAll();

            for (ClientRepresentation client : clients) {
                if (client.getClientId().startsWith("dalaillama-")) {
                    realm.clients().get(client.getId()).remove();
                    log.debug("Deleted client: {}", client.getClientId());
                }
            }
            log.info("Deleted all dalaillama clients in realm {}", realmName);

        } catch (Exception e) {
            log.error("Failed to delete clients: {}", e.getMessage());
        }
    }

    record AppPanel(String appType, String displayName, String subdomain, String url,
                    String icon, int displayOrder, String keycloakClientId,
                    String frontendImage, String requiredRoles) {}
}