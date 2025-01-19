package org.springframework.boot.crm.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.service.CallManager;
import org.springframework.boot.crm.service.MetaManager;
import org.springframework.web.bind.annotation.*;

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
    public String status( String status){
        log.info("Status received - {}", status);
        return status;
    }


}
