package com.dalai.llama.pbx.core.security;



import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 4-chain security configuration matching PBX-Core's API surface:
 *
 * Chain 1: /internal/** → permitAll
 *   Kamailio http_client (authorize/inbound, events/call-start, events/call-end)
 *   FreeSWITCH mod_xml_curl (directory, dialplan)
 *   voice-brain (AI config)
 *   These run on bare-metal outside the mesh — no JWT possible.
 *   Network-level security: only reachable from telecom namespace / host network.
 *
 * Chain 2: /api/v1/write/** → permitAll
 *   tenant-service calls these during provisioning.
 *   Secured by Istio mTLS (cross-namespace ServiceEntry).
 *   In local dev, these are open — provisioning happens via curl.
 *
 * Chain 3: /api/v1/** → JWT auth (Keycloak)
 *   Agent UI, Supervisor UI, Admin UI call these.
 *   Keycloak issues JWT with tenant_id claim — used for tenant isolation.
 *   @PreAuthorize can add role-based checks (AGENT, SUPERVISOR, ADMIN).
 *
 * Chain 4: /actuator/**, /swagger-ui/**, /v3/api-docs/** → permitAll
 *   K8s readiness/liveness probes.
 *   Swagger UI for development.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Chain 1: Internal APIs — NO auth.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 2: Write APIs — NO JWT (Istio mTLS).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain writeChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/write/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 3: Operational APIs — Keycloak JWT required.
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