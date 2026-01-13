package com.dalai.llama.pbx.core.cdr;

import com.dalai.llama.pbx.core.aop.AIAware;
import com.dalai.llama.pbx.core.model.CallRecord;
import com.dalai.llama.pbx.core.repository.CallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordService {

    private final CallRecordRepository recordRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Save a new call record once call completes.
     * Trigger post-call AI summary or analysis if enabled.
     */
    @AIAware(feature = "transcription")
    public void saveCallRecord(String tenantId, String callId, String recordPath) {
        log.info("💾 Saving final call record for tenant {} call {}", tenantId, callId);

        CallRecord rec = recordRepo.findByTenantIdAndCallId(tenantId, callId);
        if (rec == null) {
            rec = CallRecord.builder()
                    .tenantId(tenantId)
                    .callId(callId)
                    //.recordPath(recordPath)
                    .status("completed")
                    .createdAt(Instant.now())
                    .build();
        } else {
            //rec.setRecordPath(recordPath);
            rec.setStatus("completed");
        }

        recordRepo.save(rec);

        // Publish final CDR to billing
        publishCdrCompletedEvent(rec);
    }

    /**
     * Called by AI-Service via Kafka events (real-time transcription chunks).
     */
    @KafkaListener(topicPattern = "cdr.transcription.*", groupId = "pbx-core")
    public void onTranscriptUpdate(Map<String, Object> msg) {
        try {
            String tenantId = (String) msg.get("tenantId");
            String callId = (String) msg.get("callId");
            String role = (String) msg.get("role");
            String text = (String) msg.get("text");

            if (tenantId == null || callId == null || role == null || text == null) {
                log.warn("⚠️ Invalid transcription message: {}", msg);
                return;
            }

            CallRecord rec = recordRepo.findByTenantIdAndCallId(tenantId, callId);
            if (rec == null) {
                rec = CallRecord.builder()
                        .tenantId(tenantId)
                        .callId(callId)
                        .status("in-progress")
                        .createdAt(Instant.now())
                        .build();
            }
/*
            if ("caller".equalsIgnoreCase(role)) {
                rec.setTranscriptCaller(
                        (rec.getTranscriptCaller() == null ? "" : rec.getTranscriptCaller() + "\n") + text
                );
            } else if ("responder".equalsIgnoreCase(role)) {
                rec.setTranscriptResponder(
                        (rec.getTranscriptResponder() == null ? "" : rec.getTranscriptResponder() + "\n") + text
                );
            }
*/
            recordRepo.save(rec);
            log.debug("📝 Appended {} transcript for call {}: {}", role, callId, text);
        } catch (Exception e) {
            log.error("Failed to handle transcription event: {}", e.getMessage(), e);
        }
    }

//    /**
//     * Handles call recording copy from RTPengine recording folder.
//     * @param tenantId tenant identifier
//     * @param callId   call ID
//     * @param sourcePath RTPengine-recorded file path (e.g., /var/spool/rtpengine/recordings/{callId}.wav)
//     */
//    public void saveRecordingFile(String tenantId, String callId, String sourcePath) {
//        try {
//            Path src = Path.of(sourcePath);
//            Path destDir = Path.of("/data/recordings/" + tenantId);
//            Files.createDirectories(destDir);
//
//            Path dest = destDir.resolve(callId + ".wav");
//            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
//
//            CallRecord rec = recordRepo.findByTenantIdAndCallId(tenantId, callId);
//            if (rec != null) {
//                rec.setRecordPath(dest.toString());
//                recordRepo.save(rec);
//                log.info("🎧 Recording saved for tenant {} call {} at {}", tenantId, callId, dest);
//            } else {
//                log.warn("⚠️ Recording received but no CDR found for {}", callId);
//            }
//
//        } catch (Exception e) {
//            log.error("Failed to save call recording: {}", e.getMessage(), e);
//        }
//    }

    /**
     * Publishes a completed CDR event to Kafka for Billing or Analytics services.
     */
    private void publishCdrCompletedEvent(CallRecord rec) {
        String topic = "cdr.completed." + rec.getTenantId();
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("tenantId", rec.getTenantId());
            event.put("callId", rec.getCallId());
            //event.put("recordPath", rec.getRecordPath());
            event.put("durationSec", rec.getDurationSec());
            event.put("transcriptLength", getTranscriptLength(rec));
            event.put("timestamp", Instant.now().toString());

            kafkaTemplate.send(topic, rec.getTenantId() + "-" + rec.getCallId(), event);
            log.info("📤 Published completed CDR to topic {}", topic);
        } catch (Exception e) {
            log.error("Failed to publish CDR completed event: {}", e.getMessage());
        }
    }

    private int getTranscriptLength(CallRecord rec) {
        //int caller = rec.getTranscriptCaller() == null ? 0 : rec.getTranscriptCaller().length();
        //int responder = rec.getTranscriptResponder() == null ? 0 : rec.getTranscriptResponder().length();
        //return caller + responder;

        return 0;
    }

    /**
     * Retrieves all CDRs for a tenant.
     */
    public java.util.List<CallRecord> listForTenant(String tenantId) {
        return recordRepo.findByTenantId(tenantId);
    }

    /**
     * Retrieves a specific call record.
     */
    public CallRecord getCall(String tenantId, String callId) {
        return recordRepo.findByTenantIdAndCallId(tenantId, callId);
    }
}
