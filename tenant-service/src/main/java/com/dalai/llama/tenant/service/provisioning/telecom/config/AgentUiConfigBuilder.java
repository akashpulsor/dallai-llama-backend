package com.dalai.llama.tenant.service.provisioning.telecom.config;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds configuration for the Agent UI (React SPA) deployment.
 * 
 * The Agent UI is a React application that provides:
 * - WebRTC softphone for agents
 * - Real-time call controls (answer, hold, transfer, mute)
 * - Queue dashboard and statistics
 * - Agent status management
 * - Customer information popup (screen pop)
 * - Integration with CRM systems
 */
@Slf4j
@Component
public class AgentUiConfigBuilder {

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${agent-ui.image:dalaillama/agent-ui:latest}")
    private String agentUiImage;

    /**
     * Build the Nginx configuration for serving the React app
     */
    public String buildNginxConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            # Nginx configuration for Agent UI
            # Tenant: %s
            
            server {
                listen 80;
                listen [::]:80;
                server_name %s.%s;
                
                # Redirect HTTP to HTTPS
                return 301 https://$server_name$request_uri;
            }
            
            server {
                listen 443 ssl http2;
                listen [::]:443 ssl http2;
                server_name %s.%s;
                
                # SSL certificates (managed by cert-manager)
                ssl_certificate /etc/nginx/ssl/tls.crt;
                ssl_certificate_key /etc/nginx/ssl/tls.key;
                
                # SSL settings
                ssl_protocols TLSv1.2 TLSv1.3;
                ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
                ssl_prefer_server_ciphers off;
                ssl_session_timeout 1d;
                ssl_session_cache shared:SSL:50m;
                ssl_stapling on;
                ssl_stapling_verify on;
                
                # Security headers
                add_header X-Frame-Options "SAMEORIGIN" always;
                add_header X-Content-Type-Options "nosniff" always;
                add_header X-XSS-Protection "1; mode=block" always;
                add_header Referrer-Policy "strict-origin-when-cross-origin" always;
                add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' wss://*.%s https://*.%s; media-src 'self' blob:; worker-src 'self' blob:;" always;
                
                # Root directory
                root /usr/share/nginx/html;
                index index.html;
                
                # Gzip compression
                gzip on;
                gzip_vary on;
                gzip_min_length 1024;
                gzip_proxied any;
                gzip_types text/plain text/css text/xml text/javascript application/javascript application/json application/xml;
                
                # Cache static assets
                location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
                    expires 1y;
                    add_header Cache-Control "public, immutable";
                }
                
                # SPA routing - serve index.html for all routes
                location / {
                    try_files $uri $uri/ /index.html;
                }
                
                # API proxy to PBX-Core
                location /api/ {
                    proxy_pass http://pbx-core:8080/;
                    proxy_http_version 1.1;
                    proxy_set_header Host $host;
                    proxy_set_header X-Real-IP $remote_addr;
                    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                    proxy_set_header X-Forwarded-Proto $scheme;
                    proxy_set_header X-Tenant-Id "%s";
                    proxy_connect_timeout 60s;
                    proxy_send_timeout 60s;
                    proxy_read_timeout 60s;
                }
                
                # WebSocket proxy for real-time events
                location /ws/ {
                    proxy_pass http://pbx-core:8080/ws/;
                    proxy_http_version 1.1;
                    proxy_set_header Upgrade $http_upgrade;
                    proxy_set_header Connection "upgrade";
                    proxy_set_header Host $host;
                    proxy_set_header X-Real-IP $remote_addr;
                    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                    proxy_set_header X-Tenant-Id "%s";
                    proxy_read_timeout 86400;
                }
                
                # Health check endpoint
                location /health {
                    access_log off;
                    return 200 "healthy\\n";
                    add_header Content-Type text/plain;
                }
            }
            """,
            tenant.getSlug(),
            tenant.getSlug(), baseDomain,
            tenant.getSlug(), baseDomain,
            baseDomain, baseDomain,
            config.getTenantId(),
            config.getTenantId()
        );
    }

    /**
     * Build the runtime config.js that is injected into the React app
     */
    public String buildRuntimeConfig(Tenant tenant, TelecomStackConfig config) {
        String keycloakUrl = "https://auth." + baseDomain;
        String keycloakRealm = "tenant-" + tenant.getSlug();
        String keycloakClientId = "dalaillama-" + tenant.getSlug();
        
        return String.format("""
            // Runtime configuration for Agent UI
            // Tenant: %s
            // Auto-generated - do not edit manually
            
            window.__RUNTIME_CONFIG__ = {
                // Tenant identification
                TENANT_ID: "%s",
                TENANT_SLUG: "%s",
                TENANT_NAME: "%s",
                
                // API endpoints
                API_BASE_URL: "/api",
                WS_URL: "wss://%s.%s/ws",
                
                // WebRTC/SIP configuration
                SIP: {
                    SERVER: "wss://%s.wss.%s",
                    REALM: "%s",
                    STUN_SERVERS: [
                        "stun:stun.l.google.com:19302",
                        "stun:%s:3478"
                    ],
                    TURN_SERVERS: [
                        {
                            urls: "turn:%s:3478",
                            username: "", // Set dynamically after auth
                            credential: "" // Set dynamically after auth
                        },
                        {
                            urls: "turns:%s:5349",
                            username: "",
                            credential: ""
                        }
                    ],
                    ICE_TRANSPORT_POLICY: "all",
                    ODUCTION_INTERVAL: 50,
                    MEDIA_CONSTRAINTS: {
                        audio: {
                            echoCancellation: true,
                            noiseSuppression: true,
                            autoGainControl: true
                        },
                        video: false
                    }
                },
                
                // Authentication (Keycloak)
                AUTH: {
                    URL: "%s",
                    REALM: "%s",
                    CLIENT_ID: "%s",
                    REDIRECT_URI: "https://%s.%s/callback",
                    POST_LOGOUT_REDIRECT_URI: "https://%s.%s/",
                    SCOPES: "openid profile email"
                },
                
                // Feature flags
                FEATURES: {
                    ENABLE_WEBRTC: true,
                    ENABLE_SCREEN_POP: true,
                    ENABLE_CALL_RECORDING: %s,
                    ENABLE_AI_ASSIST: %s,
                    ENABLE_TRANSCRIPTION: %s,
                    ENABLE_SENTIMENT: %s,
                    ENABLE_COACHING: true,
                    ENABLE_CALLBACK: true,
                    ENABLE_SMS: false,
                    ENABLE_EMAIL: false,
                    ENABLE_CHAT: false
                },
                
                // UI settings
                UI: {
                    THEME: "light",
                    LANGUAGE: "en",
                    DATE_FORMAT: "DD/MM/YYYY",
                    TIME_FORMAT: "HH:mm:ss",
                    TIMEZONE: "%s",
                    SHOW_QUEUE_STATS: true,
                    SHOW_AGENT_STATS: true,
                    AUTO_ANSWER: false,
                    RINGTONE: "/sounds/ringtone.mp3",
                    NOTIFICATION_SOUND: "/sounds/notification.mp3"
                },
                
                // Integrations
                INTEGRATIONS: {
                    CRM_ENABLED: false,
                    CRM_URL: null,
                    HELPDESK_ENABLED: false,
                    HELPDESK_URL: null
                },
                
                // Branding
                BRANDING: {
                    LOGO_URL: "/logo.png",
                    COMPANY_NAME: "%s",
                    PRIMARY_COLOR: "#1976d2",
                    SECONDARY_COLOR: "#dc004e"
                }
            };
            
            // Freeze config to prevent modifications
            Object.freeze(window.__RUNTIME_CONFIG__);
            Object.freeze(window.__RUNTIME_CONFIG__.SIP);
            Object.freeze(window.__RUNTIME_CONFIG__.AUTH);
            Object.freeze(window.__RUNTIME_CONFIG__.FEATURES);
            Object.freeze(window.__RUNTIME_CONFIG__.UI);
            Object.freeze(window.__RUNTIME_CONFIG__.INTEGRATIONS);
            Object.freeze(window.__RUNTIME_CONFIG__.BRANDING);
            """,
            tenant.getSlug(),
            config.getTenantId(),
            tenant.getSlug(),
            tenant.getName(),
            tenant.getSlug(), baseDomain,
            tenant.getSlug(), baseDomain,
            config.getRealm(),
           // tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "turn." + tenant.getSlug() + "." + baseDomain,
           // tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "turn." + tenant.getSlug() + "." + baseDomain,
           // tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "turn." + tenant.getSlug() + "." + baseDomain,
            keycloakUrl,
            keycloakRealm,
            keycloakClientId,
            tenant.getSlug(), baseDomain,
            tenant.getSlug(), baseDomain,
            config.isEnableRecording() ? "true" : "false",
            config.isEnableAiRouting() ? "true" : "false",
            config.isEnableAiTranscription() ? "true" : "false",
            config.isEnableAiTranscription() ? "true" : "false",
            tenant.getTimezone() != null ? tenant.getTimezone() : "Asia/Kolkata",
            tenant.getCompanyName() != null ? tenant.getCompanyName() : tenant.getName()
        );
    }

    /**
     * Build environment variables for the container
     */
    public String buildEnvConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            TENANT_ID=%s
            TENANT_SLUG=%s
            API_URL=http://pbx-core:8080
            WS_URL=ws://pbx-core:8080/ws
            KEYCLOAK_URL=https://auth.%s
            KEYCLOAK_REALM=tenant-%s
            KEYCLOAK_CLIENT_ID=dalaillama-%s
            """,
            config.getTenantId(),
            tenant.getSlug(),
            baseDomain,
            tenant.getSlug(),
            tenant.getSlug()
        );
    }
}
