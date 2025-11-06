package com.dalaillama.auth.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Value("${keycloak.admin.url}")
    private String keycloakAdminUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.client-id}")
    private String adminClientId;

    @Value("${keycloak.admin.client-secret}")
    private String adminSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "auth-service");
    }

    @GetMapping("/userinfo")
    public Map<String, Object> userInfo(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "sub", jwt.getSubject(),
                "email", jwt.getClaimAsString("email"),
                "preferred_username", jwt.getClaimAsString("preferred_username"),
                "roles", jwt.getClaimAsStringList("roles")
        );
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> body) {
        try {
            // 1️⃣ Extract request data
            String email = body.get("email");
            String firstName = body.get("firstName");
            String lastName = body.get("lastName");
            String password = body.get("password");

            // 🧠 Validate input
            if (email == null || password == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required"));
            }

            // 2️⃣ Get token using CLIENT CREDENTIALS (service account)
            String tokenUrl = keycloakAdminUrl + "/realms/master/protocol/openid-connect/token";

            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // ✅ client_credentials flow (no admin username/password)
            String tokenRequest = String.format(
                    "client_id=%s&client_secret=%s&grant_type=client_credentials",
                    adminClientId, adminSecret // rename these vars in your class: clientId + clientSecret
            );

            HttpEntity<String> tokenEntity = new HttpEntity<>(tokenRequest, tokenHeaders);
            ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, tokenEntity, Map.class);

            if (!tokenResponse.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Failed to get Keycloak access token"));
            }

            String accessToken = (String) tokenResponse.getBody().get("access_token");

            // 3️⃣ Create user in Keycloak
            String createUserUrl = keycloakAdminUrl + "/admin/realms/" + realm + "/users";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            Map<String, Object> userPayload = Map.of(
                    "username", email,
                    "email", email,
                    "firstName", firstName,
                    "lastName", lastName,
                    "enabled", true,
                    "credentials", new Object[]{
                            Map.of(
                                    "type", "password",
                                    "value", password,
                                    "temporary", false
                            )
                    }
            );

            HttpEntity<Map<String, Object>> createUserEntity = new HttpEntity<>(userPayload, headers);
            ResponseEntity<String> createResponse = restTemplate.exchange(createUserUrl, HttpMethod.POST, createUserEntity, String.class);

            if (createResponse.getStatusCode().is2xxSuccessful() || createResponse.getStatusCode() == HttpStatus.CREATED) {
                return ResponseEntity.ok(Map.of("message", "User created successfully"));
            } else if (createResponse.getStatusCode() == HttpStatus.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "User already exists"));
            }

            return ResponseEntity.status(createResponse.getStatusCode())
                    .body(Map.of("error", "Unexpected Keycloak response", "status", createResponse.getStatusCodeValue()));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }


}
