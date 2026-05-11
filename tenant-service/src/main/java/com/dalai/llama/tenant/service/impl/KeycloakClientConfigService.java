package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.AppPanel;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

/**
 * Keycloak Client Configuration Service
 *
 * Creates OIDC clients for tenant frontend apps. Since each tenant has its own
 * realm, client IDs are realm-scoped and need not embed the tenant slug.
 *
 * ClientId derivation:
 *   subdomain part of panel.url -> strip "-{tenantSlug}" suffix -> strip trailing "-ui" -> append "-ui"
 *
 * Examples (tenant slug = "acme"):
 *   admin-acme.dalaillama.in       -> admin-ui
 *   agent-acme.dalaillama.in       -> agent-ui
 *   supervisor-acme.dalaillama.in  -> supervisor-ui
 *   admin-ui-acme.dalaillama.in    -> admin-ui  (no double -ui)
 *
 * Role hierarchy:
 *   ADMIN > SUPERVISOR > AGENT
 *
 * Redirect URIs include localhost:5170-5179 for local dev (Vite dev servers).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakClientConfigService {

    private final Keycloak keycloakAdmin;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${keycloak.public-url:https://auth.dalaillama.in}")
    private String keycloakPublicUrl;

    private static final int DEV_PORT_START = 5170;
    private static final int DEV_PORT_END = 5179;

    /**
     * Create or update Keycloak OIDC clients for the given panels, enrich each
     * panel with keycloakClientId + requiredRoles, and return the keycloak
     * coordinates + enriched panels for the orchestrator to persist.
     *
     * @param tenant            the tenant (provides slug + realm name)
     * @param tenantApp         originating TenantApp (linked back-reference on each panel)
     * @param panelsToProvision panel definitions for this app — enriched in place
     */
    public KeycloakProvisioningResult createClientsForTenant(
            Tenant tenant,
            TenantApp tenantApp,
            List<AppPanel> panelsToProvision
    ) {
        String tenantSlug = tenant.getSlug();
        String realmName = tenant.getKeycloakRealmName();
        log.info("Creating Keycloak clients for tenant {} in realm {} ({} panels)",
                tenantSlug, realmName,
                panelsToProvision != null ? panelsToProvision.size() : 0);

        String issuer = keycloakPublicUrl + "/realms/" + realmName;

        if (panelsToProvision == null || panelsToProvision.isEmpty()) {
            log.warn("No panels to provision for tenant {} app {}", tenantSlug,
                    tenantApp != null ? tenantApp.getId() : "null");
            return new KeycloakProvisioningResult(
                    keycloakPublicUrl, realmName, issuer, List.of()
            );
        }

        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            Set<String> processedClientIds = new HashSet<>();

            for (AppPanel panel : panelsToProvision) {
                // Enrich in-place
                String clientId = deriveClientId(panel, tenantSlug);
                panel.setKeycloakClientId(clientId);
                panel.setRequiredRoles(rolesForAppType(panel.getAppType()));

                // Back-reference for traceability
                if (tenantApp != null) {
                    panel.setTenantApp(tenantApp);
                }

                if (processedClientIds.contains(clientId)) {
                    log.debug("Skipping duplicate clientId {} within same provisioning run", clientId);
                    continue;
                }

                List<ClientRepresentation> existing = realm.clients().findByClientId(clientId);
                if (!existing.isEmpty()) {
                    updateClient(realm, existing.get(0), panel, tenantSlug);
                } else {
                    createClient(realm, clientId, panel, tenantSlug);
                }
                processedClientIds.add(clientId);
            }

            log.info("Provisioned {} Keycloak clients for tenant {}", processedClientIds.size(), tenantSlug);

            return new KeycloakProvisioningResult(
                    keycloakPublicUrl, realmName, issuer, panelsToProvision
            );

        } catch (Exception e) {
            log.error("Failed to create Keycloak clients for {}: {}", tenantSlug, e.getMessage(), e);
            throw new RuntimeException("Keycloak client creation failed for tenant " + tenantSlug, e);
        }
    }

    /**
     * Derive clientId from panel URL hostname:
     *   1. Extract first hostname label (subdomain part before first dot)
     *   2. Lowercase
     *   3. Strip trailing "-{tenantSlug}" if present
     *   4. Strip trailing "-ui" if present (avoid double-suffix)
     *   5. Append "-ui"
     */
    private String deriveClientId(AppPanel panel, String tenantSlug) {
        String label = extractFirstHostLabel(panel.getUrl());
        if (label == null || label.isBlank()) {
            label = panel.getSubdomain() != null ? panel.getSubdomain() : "app";
        }
        label = label.toLowerCase(Locale.ROOT).trim();

        String slugSuffix = "-" + tenantSlug.toLowerCase(Locale.ROOT);
        if (label.endsWith(slugSuffix)) {
            label = label.substring(0, label.length() - slugSuffix.length());
        }

        if (label.endsWith("-ui")) {
            label = label.substring(0, label.length() - 3);
        }

        if (label.isBlank()) label = "app";
        return label + "-ui";
    }

    /**
     * Extract the first hostname label from a URL.
     *   https://admin-acme.dalaillama.in/foo -> "admin-acme"
     *   http://localhost:5173                -> "localhost"
     */
    private String extractFirstHostLabel(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return null;
            int dot = host.indexOf('.');
            return dot < 0 ? host : host.substring(0, dot);
        } catch (Exception e) {
            log.warn("Could not parse URL {} for clientId derivation: {}", url, e.getMessage());
            return null;
        }
    }

    private String rolesForAppType(String appType) {
        if (appType == null) return "AGENT";
        return switch (appType.toUpperCase()) {
            case "ADMIN_PANEL"    -> "ADMIN,SUPERVISOR,AGENT";
            case "SUPERVISOR"     -> "SUPERVISOR,AGENT";
            case "CONTACT_CENTER" -> "AGENT";
            default               -> "AGENT";
        };
    }

    private void createClient(RealmResource realm, String clientId, AppPanel panel, String tenantSlug) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setName(panel.getDisplayName());
        client.setDescription("Client for " + panel.getAppType() + " - " + tenantSlug);
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(false);
        client.setStandardFlowEnabled(true);

        client.setAttributes(Map.of(
                "pkce.code.challenge.method", "S256",
                "post.logout.redirect.uris", panel.getUrl() + "/*##http://localhost:*##http://127.0.0.1:*"
        ));

        client.setRedirectUris(buildRedirectUris(panel.getUrl()));
        client.setWebOrigins(buildWebOrigins(panel.getUrl()));
        client.setRootUrl(panel.getUrl());
        client.setBaseUrl(panel.getUrl());

        client.setProtocolMappers(createProtocolMappers(tenantSlug, panel));

        realm.clients().create(client);
        log.info("Created Keycloak client: {} with prod + localhost dev redirects", clientId);
    }

    private void updateClient(RealmResource realm, ClientRepresentation existing,
                              AppPanel panel, String tenantSlug) {
        String appUrl = panel.getUrl();

        Set<String> redirectUris = new LinkedHashSet<>(
                existing.getRedirectUris() != null ? existing.getRedirectUris() : List.of());
        redirectUris.addAll(buildRedirectUris(appUrl));
        existing.setRedirectUris(new ArrayList<>(redirectUris));

        Set<String> webOrigins = new LinkedHashSet<>(
                existing.getWebOrigins() != null ? existing.getWebOrigins() : List.of());
        webOrigins.addAll(buildWebOrigins(appUrl));
        existing.setWebOrigins(new ArrayList<>(webOrigins));

        realm.clients().get(existing.getId()).update(existing);
        log.debug("Updated Keycloak client: {}", existing.getClientId());
    }

    private List<String> buildRedirectUris(String appUrl) {
        List<String> uris = new ArrayList<>();
        uris.add(appUrl + "/*");
        uris.add(appUrl + "/callback");
        uris.add(appUrl + "/silent-check-sso.html");

        for (int port = DEV_PORT_START; port <= DEV_PORT_END; port++) {
            uris.add("http://localhost:" + port + "/*");
            uris.add("http://localhost:" + port + "/callback");
            uris.add("http://localhost:" + port + "/silent-check-sso.html");
            uris.add("http://127.0.0.1:" + port + "/*");
            uris.add("http://127.0.0.1:" + port + "/callback");
            uris.add("http://127.0.0.1:" + port + "/silent-check-sso.html");
        }
        return uris;
    }

    private List<String> buildWebOrigins(String appUrl) {
        List<String> origins = new ArrayList<>();
        origins.add(appUrl);
        for (int port = DEV_PORT_START; port <= DEV_PORT_END; port++) {
            origins.add("http://localhost:" + port);
            origins.add("http://127.0.0.1:" + port);
        }
        return origins;
    }

    private List<ProtocolMapperRepresentation> createProtocolMappers(String tenantSlug, AppPanel panel) {
        List<ProtocolMapperRepresentation> mappers = new ArrayList<>();

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

        ProtocolMapperRepresentation appMapper = new ProtocolMapperRepresentation();
        appMapper.setName("app_type");
        appMapper.setProtocol("openid-connect");
        appMapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        appMapper.setConfig(Map.of(
                "claim.name", "app_type",
                "claim.value", panel.getAppType(),
                "jsonType.label", "String",
                "id.token.claim", "true",
                "access.token.claim", "true"
        ));
        mappers.add(appMapper);

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

    public void createDefaultRoles(String realmName) {
        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            String[] roles = { "ADMIN", "SUPERVISOR", "AGENT" };

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
                    // already exists
                }
            }
            log.info("Ensured default roles exist in realm {}", realmName);
        } catch (Exception e) {
            log.error("Failed to create roles in {}: {}", realmName, e.getMessage());
        }
    }

    public FrontendConfig getFrontendConfig(String tenantSlug, String keycloakRealmName, String appType) {
        String subdomain = switch (appType.toUpperCase()) {
            case "CONTACT_CENTER" -> "agent";
            case "SUPERVISOR"     -> "supervisor";
            case "ADMIN_PANEL"    -> "admin";
            default               -> appType.toLowerCase();
        };
        String clientId = subdomain + "-ui";

        return new FrontendConfig(
                keycloakPublicUrl,
                keycloakRealmName,
                clientId,
                keycloakPublicUrl + "/realms/" + keycloakRealmName
        );
    }

    public record FrontendConfig(
            String keycloakUrl,
            String realm,
            String clientId,
            String issuer
    ) {}

    public record KeycloakProvisioningResult(
            String keycloakUrl,
            String keycloakRealm,
            String keycloakIssuer,
            List<AppPanel> panels
    ) {}

    public void deleteClient(String realmName, String clientId) {
        if (clientId == null || clientId.isBlank()) return;
        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
            for (ClientRepresentation client : found) {
                realm.clients().get(client.getId()).remove();
                log.info("Deleted Keycloak client {} from realm {}", clientId, realmName);
            }
        } catch (Exception e) {
            log.warn("Failed to delete Keycloak client {}: {}", clientId, e.getMessage());
        }
    }

    /**
     * Delete all dalaillama-managed clients in a realm. Since each tenant has
     * its own realm, deleting by realm wipes all tenant clients anyway —
     * but this method is kept for selective cleanup.
     */
    public void deleteClientsForTenant(String realmName) {
        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            List<ClientRepresentation> clients = realm.clients().findAll();
            for (ClientRepresentation client : clients) {
                String cid = client.getClientId();
                if (cid != null && cid.endsWith("-ui")) {
                    realm.clients().get(client.getId()).remove();
                    log.debug("Deleted client: {}", cid);
                }
            }
            log.info("Deleted tenant -ui clients in realm {}", realmName);
        } catch (Exception e) {
            log.error("Failed to delete clients: {}", e.getMessage());
        }
    }
}