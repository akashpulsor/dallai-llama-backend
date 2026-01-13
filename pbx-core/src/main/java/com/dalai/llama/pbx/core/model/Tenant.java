package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Entity
@Table(name = "tenants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String realm;
    private String deploymentModel; // shared | dedicated
    private String region;
    private String status; // provisioning | active | error

    // ------------------ Feature Toggles ------------------
    private boolean aiRoutingEnabled;
    private boolean aiTranscriptionEnabled;
    private boolean aiNoiseCancellationEnabled;

    // ------------------ Credentials ------------------
    private String kafkaUsername;
    private String kafkaPassword;

    private String redisUsername;
    private String redisPassword;

    private String postgresUsername;
    private String postgresPassword;

    private String mysqlUsername;
    private String mysqlPassword;

    // ------------------ Dynamic Kafka Topics ------------------
    private String kafkaRegTopic;
    private String kafkaCallTopic;
    private String kafkaAiResultTopic;
    private String kafkaRtpTopic;

    // ------------------ Infra URLs ------------------
    private String kafkaBootstrap;
    private String redisUrl;
    private String postgresUrl;
    private String mysqlUrl;

    // ------------------ SBC / SIP / WebRTC URLs ------------------
    private String sipUdpUrl;      // sip:kamailio-x:5060
    private String sipTlsUrl;      // sips:kamailio-x:5061
    private String websocketUrl;   // wss://...
    private String rtpengineSock;  // udp:rtpengine-x:22222
    private String pbxCoreUrlInternal;    // http://pbx-core-x:8080
}
