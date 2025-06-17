package org.springframework.boot.crm.controller;


import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.CallStatusDto;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.CallManager;
import org.springframework.boot.crm.service.TranscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/call")
public class CallController {

    private final CallManager callManager;
    private final BusinessManager businessManager;

    private  final TranscriptionService transcriptionService;

    public CallController(CallManager callManager, BusinessManager businessManager, TranscriptionService transcriptionService) {
        this.callManager = callManager;
        this.businessManager = businessManager;
        this.transcriptionService = transcriptionService;
    }




    @PostMapping("/incoming")
    public String incomingCall(@RequestHeader("host") String host,@RequestParam("campaignRunId") int campaignRunId,
                               @RequestParam("authToken") String authToken,@RequestParam("businessId") int businessId,@RequestParam("leadId") int leadId,
                               @RequestParam("callType") String callType){
        return this.businessManager.incomingCall(host,campaignRunId,authToken,businessId,leadId,callType);
    }


    @PostMapping("/status")
    public String status(@RequestHeader("X-Twilio-Signature") String twilioSignature,
                                         @RequestParam Map<String, String> params,
                                         HttpServletRequest request){
        // Extract call details
        String callSid = params.get("CallSid");
        String callStatus = params.get("CallStatus");
        String callDuration = params.get("CallDuration");
        String timestamp = params.get("Timestamp");
        String fromNumber = params.get("From");
        String toNumber = params.get("To");
        String direction = params.get("Direction");
        String queueTime = params.get("QueueTime");
        String streamSid = params.get("StreamSid");
        log.info("Status call back -{} - {} -{} -{} -{} -{} -{} -{} -{}",callSid,
                callStatus,callDuration,timestamp, fromNumber, toNumber, direction,queueTime, streamSid);
        // Validate the request
        CallStatusDto callStatusData = new CallStatusDto(callSid, callStatus, callDuration, timestamp, fromNumber, toNumber, direction, queueTime, streamSid);
        this.businessManager.getCallManager().updateCallStatus(callStatusData);



        return "ok";
    }

    // Add this new controller method to handle recording status callbacks
    @PostMapping("/recording-status")
    public ResponseEntity<String> handleRecordingStatus(
            @RequestHeader("host") String hostname,
            @RequestParam("RecordingSid") String recordingSid,
            @RequestParam("RecordingStatus") String recordingStatus,
            @RequestParam("RecordingUrl") String recordingUrl,
            @RequestParam("CallSid") String callSid) {

        log.info("Recording status update for call {}: {} - Recording SID: {} - url -{}",
                callSid, recordingStatus, recordingSid, recordingUrl);

        if ("completed".equals(recordingStatus)) {
            try {
                this.businessManager.getTranscription( hostname,callSid,recordingSid, recordingStatus, recordingUrl);
            } catch (Exception e) {
                log.error("Error creating transcription for recording {}: {}",
                        recordingSid, e.getMessage(), e);
            }
        }

        return ResponseEntity.ok("Recording status processed");
    }


}
