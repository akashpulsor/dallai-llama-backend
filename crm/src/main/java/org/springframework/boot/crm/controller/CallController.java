package org.springframework.boot.crm.controller;


import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.CallStatusDto;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CallStatus;
import org.springframework.boot.crm.service.CallManager;
import org.springframework.boot.crm.service.MetaManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/call")
public class CallController {

    private final CallManager callManager;

    public CallController(CallManager callManager) {
        this.callManager = callManager;
    }




    @PostMapping("/incoming")
    public String incomingCall(@RequestHeader("host") String host,@RequestParam("campaignRunId") int campaignRunId,
                               @RequestParam("authToken") String authToken,@RequestParam("businessId") int businessId,@RequestParam("leadId") int leadId,
                               @RequestParam("callType") String callType){
        return this.callManager.incomingCall(host,campaignRunId,authToken,businessId,leadId,callType);
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
        this.callManager.updateCallStatus(callStatusData);
        return "ok";
    }


}
