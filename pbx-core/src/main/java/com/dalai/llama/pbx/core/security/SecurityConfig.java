package com.dalai.llama.pbx.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * 4-chain security configuration matching PBX-Core's API surface:
 *
 * Chain 1: /freeswitch/**, /kamailio/**, /rtpengine/** → IP-restricted, NO JWT
 *   FreeSWITCH mod_xml_curl (directory, dialplan)
 *   Kamailio http_client (authorize/inbound, auth/digest, events)
 *   RTPEngine config
 *   These run on bare-metal outside the cluster — no JWT possible.
 *   Access restricted to localhost + configured trusted IPs (telecom hosts).
 *   Configure via env: TRUSTED_NETWORKS or pbxcore.security.trusted-networks
 *
 * Chain 2: /internal/**, /api/v1/write/**, /api/v1/internal/** → permitAll
 *   voice-brain (Pipecat) calls /internal/ai/** (service-to-service, no JWT).
 *   tenant-service calls /api/v1/internal/provisioning/** during tenant setup.
 *   tenant-service calls /api/v1/write/** for Kamailio table writes.
 *   Secured by Istio mTLS in production; open in local dev.
 *
 * Chain 3: /api/v1/** → JWT auth (Keycloak)
 *   Agent UI, Supervisor UI, Admin UI call these.
 *   Includes /api/v1/ai/** (JWT-protected AI config for UI).
 *   Keycloak issues JWT with tenant_id claim — used for tenant isolation.
 *   @PreAuthorize can add role-based checks (AGENT, SUPERVISOR, ADMIN).
 *
 * Chain 4: /actuator/**, /swagger-ui/**, /v3/api-docs/**, /ws/** → permitAll
 *   K8s readiness/liveness probes, Swagger UI, WebSocket STOMP.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${pbxcore.security.trusted-networks:127.0.0.1,::1,0:0:0:0:0:0:0:1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
    private List<String> trustedNetworks;

    /**
     * Chain 1: Telecom infra endpoints — IP-restricted, NO JWT.
     * FreeSWITCH, Kamailio, RTPEngine run on bare-metal / host network.
     * Only requests from trusted IPs (localhost, pod network, telecom hosts) are allowed.
     *
     * Configure additional IPs via env: TRUSTED_NETWORKS
     */
    @Bean
    @Order(1)
    public SecurityFilterChain telecomInfraChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/freeswitch/**", "/kamailio/**", "/rtpengine/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access(new TrustedIpAuthorizationManager(trustedNetworks))
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 2: Internal + Write + Provisioning APIs — NO JWT.
     * /internal/ai/** — voice-brain (Pipecat) service-to-service calls.
     * /api/v1/internal/** — tenant-service provisioning.
     * /api/v1/write/** — Kamailio table writes.
     * Secured by Istio mTLS in production; open in local dev.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**", "/api/v1/write/**", "/api/v1/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 3: Operational APIs — Keycloak JWT required.
     * Includes /api/v1/ai/** for UI-facing AI config.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        // Agent endpoints
                        .requestMatchers("/api/v1/agents/**").authenticated()
                        .requestMatchers("/api/v1/calls/**").authenticated()
                        .requestMatchers("/api/v1/queues/**").authenticated()
                        // Supervisor endpoints (could add role check: hasRole('SUPERVISOR'))
                        .requestMatchers("/api/v1/supervisor/**").authenticated()
                        // Admin endpoints
                        .requestMatchers("/api/v1/routing/**").authenticated()
                        .requestMatchers("/api/v1/trunks/**").authenticated()
                        .requestMatchers("/api/v1/bots/**").authenticated()
                        .requestMatchers("/api/v1/campaigns/**").authenticated()
                        .requestMatchers("/api/v1/dnc/**").authenticated()
                        // TURN credentials
                        .requestMatchers("/api/v1/turn/**").authenticated()
                        // AI config (UI-facing)
                        .requestMatchers("/api/v1/ai/**").authenticated()
                        // Catch-all
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    /**
     * Chain 4: Management + docs — open.
     */
    @Bean
    @Order(4)
    public SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/ws/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}