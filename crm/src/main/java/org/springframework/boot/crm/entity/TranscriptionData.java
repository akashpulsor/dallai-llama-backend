package org.springframework.boot.crm.entity;

import com.twilio.rest.api.v2010.account.Recording;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "transcription_data")
public class TranscriptionData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transcription_id")
    private int transcriptionId;

    @Column(name = "account_sid")
    private  String accountSid;

    @Column(name = "api_version")
    private  String apiVersion;

    @Column(name = "call_sid")
    private  String callSid;

    @Column(name = "conference_sid")
    private  String conferenceSid;

    @Column(name = "date_created")
    private  ZonedDateTime dateCreated;

    @Column(name = "date_updated")
    private  ZonedDateTime dateUpdated;

    @Column(name = "start_time")
    private  ZonedDateTime startTime;

    @Column(name = "duration")
    private  String duration;

    @Column(name = "sid")
    private  String sid;

    @Column(name = "price")
    private  String price;

    @Column(name = "price_unit")
    private  String priceUnit;

    @Column(name = "status")
    private  String status;

    @Column(name = "channels")
    private  Integer channels;

    @Column(name = "source")
    private  String source;

    @Column(name = "error_code")
    private  Integer errorCode;

    @Column(name = "uri")
    private  String uri;
    //private  Map<String, Object> encryptionDetails;
    //private  Map<String, String> subresourceUris;

    @Column(name = "media_url")
    private  String mediaUrl;

    @Column(name = "call_id")
    private int callId;

    @Column(name = "campaign_id")
    private int campaignId;

    @Column(name = "campaign_run_id")
    private int campaignRunId;

    @Column(name = "business_id")
    private int businessId;

    @Column(name = "phone_id")
    private int phoneId;

    @Column(name = "llm_id")
    private int llmId;

    @Column(name = "lead_id")
    private int leadId;

    @Column(name = "inbound_transcription_text")
    private String inboundTranscriptionText;

    @Column(name = "outbound_transcription_text")
    private String outboundTranscriptionText;
    @CreatedDate
    @Column(name="created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name="updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if(createdAt==null){
            createdAt = LocalDateTime.now();
        }
        updatedAt= LocalDateTime.now();
    }
    // get mysql query to create this entity

}
