package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * TenantApp - Single entry per subscription containing ALL configuration.
 *
 * Contains:
 * - Subscription & plan reference
 * - All entitlements (from product-service)
 * - All provisioned resources (DID, SIP, Channels, Trunks)
 * - Infrastructure URLs (from K8s discovery)
 * - Generated telecom configs (Kamailio, FreePBX)
 * - App panels (UI apps)
 */
@Entity
@Table(name = "tenant_apps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantApp {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    // ==================== SUBSCRIPTION ====================

    @Column(name = "subscription_id", unique = true)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 50)
    private AppType appType;

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_model", length = 20)
    @Builder.Default
    private DeploymentModel deploymentModel = DeploymentModel.SHARED;

    @Column(name = "subdomain", nullable = false, length = 50)
    private String subdomain;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    // ==================== PRODUCT & PLAN ====================

    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_code", length = 50)
    private String planCode;

    @Column(name = "plan_tier", length = 30)
    private String planTier;

    @Column(name = "plan_assigned_at")
    private OffsetDateTime planAssignedAt;

    // ==================== CAPACITY ENTITLEMENTS ====================

    @Column(name = "agent_seats")
    private Integer agentSeats;

    @Column(name = "max_agents")
    private Integer maxAgents;

    @Column(name = "max_supervisors")
    private Integer maxSupervisors;

    @Column(name = "max_dids")
    private Integer maxDids;

    @Column(name = "max_channels")
    private Integer maxChannels;

    @Column(name = "max_queues")
    private Integer maxQueues;

    @Column(name = "max_ivr_flows")
    private Integer maxIvrFlows;

    @Column(name = "max_ring_groups")
    private Integer maxRingGroups;

    @Column(name = "max_extensions")
    private Integer maxExtensions;

    // ==================== USAGE LIMITS ====================

    @Column(name = "included_minutes")
    private Integer includedMinutes;

    @Column(name = "included_minutes_inbound")
    private Integer includedMinutesInbound;

    @Column(name = "included_minutes_outbound")
    private Integer includedMinutesOutbound;

    @Column(name = "rate_per_minute_inbound", precision = 10, scale = 4)
    private BigDecimal ratePerMinuteInbound;

    @Column(name = "rate_per_minute_outbound", precision = 10, scale = 4)
    private BigDecimal ratePerMinuteOutbound;

    @Column(name = "ai_rate_per_min", precision = 10, scale = 4)
    private BigDecimal aiRatePerMinute;

    @Column(name = "ai_tokens_per_month")
    private Integer aiTokensPerMonth;

    // ==================== RECORDING ====================

    @Column(name = "recording_storage_gb")
    private Integer recordingStorageGb;

    @Column(name = "recording_retention_days")
    private Integer recordingRetentionDays;

    // ==================== DID ====================

    @Column(name = "did_id")
    private UUID didId;

    @Column(name = "did_number", length = 20)
    private String didNumber;

    @Column(name = "did_display_number", length = 30)
    private String didDisplayNumber;

    @Column(name = "did_country", length = 5)
    private String didCountry;

    @Column(name = "did_region", length = 100)
    private String didRegion;

    @Column(name = "did_city", length = 100)
    private String didCity;

    // ==================== SIP ENDPOINT (Inbound DID Registration) ====================

    @Column(name = "sip_endpoint_id")
    private UUID sipEndpointId;

    @Column(name = "sip_endpoint_username", length = 100)
    private String sipEndpointUsername;

    @Column(name = "sip_endpoint_password_hash", length = 255)
    private String sipEndpointPasswordHash;

    @Column(name = "sip_endpoint_domain", length = 100)
    private String sipEndpointDomain;

    @Column(name = "sip_endpoint_realm", length = 100)
    private String sipEndpointRealm;

    // ==================== CHANNELS ====================

    @Column(name = "channel_bundle_id")
    private UUID channelBundleId;

    @Column(name = "channel_direction", length = 20)
    private String channelDirection;

    @Column(name = "channel_total")
    private Integer channelTotal;

    @Column(name = "channel_inbound")
    private Integer channelInbound;

    @Column(name = "channel_outbound")
    private Integer channelOutbound;

    // ==================== TENANT SIP TRUNK (Customer credentials to YOUR platform) ====================

    @Column(name = "tenant_trunk_id")
    private UUID tenantTrunkId;

    @Column(name = "tenant_trunk_username", length = 100)
    private String tenantTrunkUsername;

    @Column(name = "tenant_trunk_password_hash", length = 255)
    private String tenantTrunkPasswordHash;

    @Column(name = "tenant_trunk_domain", length = 100)
    private String tenantTrunkDomain;

    @Column(name = "tenant_trunk_port")
    private Integer tenantTrunkPort;

    @Column(name = "tenant_trunk_realm", length = 100)
    private String tenantTrunkRealm;

    @Column(name = "tenant_trunk_transport", length = 10)
    private String tenantTrunkTransport;

    @Column(name = "tenant_trunk_max_calls")
    private Integer tenantTrunkMaxCalls;

    // ==================== PLATFORM TRUNK (Epsilon - outbound) ====================

    @Column(name = "platform_trunk_id")
    private UUID platformTrunkId;

    @Column(name = "platform_trunk_provider", length = 50)
    private String platformTrunkProvider;

    @Column(name = "platform_trunk_server", length = 100)
    private String platformTrunkServer;

    @Column(name = "platform_trunk_port")
    private Integer platformTrunkPort;

    @Column(name = "platform_trunk_transport", length = 10)
    private String platformTrunkTransport;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platform_trunk_codecs")
    private List<String> platformTrunkCodecs;

    // ==================== INFRASTRUCTURE ====================

    @Column(name = "namespace", length = 100)
    private String namespace;

    @Column(name = "kafka_bootstrap", length = 200)
    private String kafkaBootstrap;

    @Column(name = "redis_url", length = 200)
    private String redisUrl;

    @Column(name = "postgres_url", length = 500)
    private String postgresUrl;

    @Column(name = "mysql_url", length = 500)
    private String mysqlUrl;

    // ==================== SIP & TELEPHONY URLs ====================

    @Column(name = "sip_external_ip", length = 100)
    private String sipExternalIp;

    @Column(name = "sip_udp_url", length = 200)
    private String sipUdpUrl;

    @Column(name = "sip_tls_url", length = 200)
    private String sipTlsUrl;

    @Column(name = "turn_url", length = 200)
    private String turnUrl;

    @Column(name = "websocket_url", length = 200)
    private String websocketUrl;

    @Column(name = "rtpengine_sock", length = 200)
    private String rtpengineSock;

    // ==================== FREESWITCH ESL ====================

    @Column(name = "freeswitch_esl_host", length = 100)
    private String freeswitchEslHost;

    @Column(name = "freeswitch_esl_port")
    private Integer freeswitchEslPort;

    @Column(name = "freeswitch_esl_password", length = 100)
    private String freeswitchEslPassword;

    // ==================== EXTERNAL INTEGRATIONS ====================

    @Column(name = "didww_trunk_id", length = 100)
    private String didwwTrunkId;

    @Column(name = "didww_sip_config_id", length = 100)
    private String didwwSipConfigId;

    // ==================== KEYCLOAK ====================

    @Column(name = "keycloak_client_id", length = 100)
    private String keycloakClientId;

    // ==================== FRONTEND ====================

    @Column(name = "frontend_service", length = 100)
    private String frontendService;

    @Column(name = "frontend_image", length = 200)
    private String frontendImage;

    @Column(name = "frontend_port")
    @Builder.Default
    private Integer frontendPort = 80;

    @Column(name = "dashboard_url", length = 200)
    private String dashboardUrl;

    // ==================== AI FEATURES ====================

    @Column(name = "ai_transcription_enabled")
    @Builder.Default
    private Boolean aiTranscriptionEnabled = false;

    @Column(name = "ai_routing_enabled")
    @Builder.Default
    private Boolean aiRoutingEnabled = false;

    @Column(name = "ai_noise_cancellation_enabled")
    @Builder.Default
    private Boolean aiNoiseCancellationEnabled = false;

    @Column(name = "ai_sentiment_enabled")
    @Builder.Default
    private Boolean aiSentimentEnabled = false;

    @Column(name = "ai_bot_enabled")
    @Builder.Default
    private Boolean aiBotEnabled = false;

    @Column(name = "ai_voice_morph_enabled")
    @Builder.Default
    private Boolean aiVoiceMorphEnabled = false;

    @Column(name = "ai_agent_assist_enabled")
    @Builder.Default
    private Boolean aiAgentAssistEnabled = false;

    // ==================== CALL FEATURES ====================

    @Column(name = "barge_enabled")
    @Builder.Default
    private Boolean bargeEnabled = false;

    @Column(name = "whisper_enabled")
    @Builder.Default
    private Boolean whisperEnabled = false;

    @Column(name = "listen_enabled")
    @Builder.Default
    private Boolean listenEnabled = false;

    @Column(name = "conference_enabled")
    @Builder.Default
    private Boolean conferenceEnabled = false;

    @Column(name = "callback_enabled")
    @Builder.Default
    private Boolean callbackEnabled = false;

    @Column(name = "blind_transfer_enabled")
    @Builder.Default
    private Boolean blindTransferEnabled = true;

    @Column(name = "attended_transfer_enabled")
    @Builder.Default
    private Boolean attendedTransferEnabled = false;

    @Column(name = "warm_transfer_enabled")
    @Builder.Default
    private Boolean warmTransferEnabled = false;

    // ==================== RECORDING ====================

    @Column(name = "recording_enabled")
    @Builder.Default
    private Boolean recordingEnabled = false;

    @Column(name = "screen_recording_enabled")
    @Builder.Default
    private Boolean screenRecordingEnabled = false;

    // ==================== IVR FEATURES ====================

    @Column(name = "basic_ivr_enabled")
    @Builder.Default
    private Boolean basicIvrEnabled = true;

    @Column(name = "conversational_ivr_enabled")
    @Builder.Default
    private Boolean conversationalIvrEnabled = false;

    @Column(name = "ivr_multi_language_enabled")
    @Builder.Default
    private Boolean ivrMultiLanguageEnabled = false;

    // ==================== DIALER FEATURES ====================

    @Column(name = "progressive_dialer_enabled")
    @Builder.Default
    private Boolean progressiveDialerEnabled = false;

    @Column(name = "predictive_dialer_enabled")
    @Builder.Default
    private Boolean predictiveDialerEnabled = false;

    @Column(name = "preview_dialer_enabled")
    @Builder.Default
    private Boolean previewDialerEnabled = false;

    @Column(name = "amd_enabled")
    @Builder.Default
    private Boolean amdEnabled = false;

    @Column(name = "dnc_management_enabled")
    @Builder.Default
    private Boolean dncManagementEnabled = false;

    // ==================== VOICEMAIL ====================

    @Column(name = "voicemail_enabled")
    @Builder.Default
    private Boolean voicemailEnabled = true;

    @Column(name = "voicemail_transcription_enabled")
    @Builder.Default
    private Boolean voicemailTranscriptionEnabled = false;

    // ==================== INTEGRATIONS ====================

    @Column(name = "crm_integration_enabled")
    @Builder.Default
    private Boolean crmIntegrationEnabled = false;

    @Column(name = "screen_pop_enabled")
    @Builder.Default
    private Boolean screenPopEnabled = false;

    @Column(name = "api_access_enabled")
    @Builder.Default
    private Boolean apiAccessEnabled = false;

    @Column(name = "webhook_enabled")
    @Builder.Default
    private Boolean webhookEnabled = false;

    // ==================== REPORTING ====================

    @Column(name = "basic_reporting_enabled")
    @Builder.Default
    private Boolean basicReportingEnabled = true;

    @Column(name = "advanced_reporting_enabled")
    @Builder.Default
    private Boolean advancedReportingEnabled = false;

    @Column(name = "custom_reports_enabled")
    @Builder.Default
    private Boolean customReportsEnabled = false;

    @Column(name = "wallboard_enabled")
    @Builder.Default
    private Boolean wallboardEnabled = false;

    // ==================== DEPLOYMENT FLAGS ====================

    @Column(name = "dedicated_infrastructure")
    @Builder.Default
    private Boolean dedicatedInfrastructure = false;

    @Column(name = "custom_domain_enabled")
    @Builder.Default
    private Boolean customDomainEnabled = false;

    @Column(name = "sla_tier", length = 20)
    @Builder.Default
    private String slaTier = "STANDARD";

    // ==================== CONFIGS (JSON/TEXT) ====================

    @Column(name = "cc_builder_config", columnDefinition = "TEXT")
    private String ccBuilderConfig;

    @Column(name = "kamailio_config", columnDefinition = "TEXT")
    private String kamailioConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "app_panels")
    private String appPanels;

    // ==================== UI & META ====================

    @Column(name = "required_roles", length = 200)
    private String requiredRoles;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    // ==================== DEPLOYMENT STATUS ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_status", length = 20)
    @Builder.Default
    private ProvisioningTaskStatus deploymentStatus = ProvisioningTaskStatus.PENDING;

    @Column(name = "kamailio_synced")
    @Builder.Default
    private Boolean kamailioSynced = false;

    @Column(name = "kamailio_synced_at")
    private Instant kamailioSyncedAt;

    @Column(name = "freepbx_synced")
    @Builder.Default
    private Boolean freepbxSynced = false;

    @Column(name = "freepbx_synced_at")
    private Instant freepbxSyncedAt;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    // ==================== TIMESTAMPS ====================

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}