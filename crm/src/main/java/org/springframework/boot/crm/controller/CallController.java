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
    public String incomingCall(@RequestHeader("host") String host,@RequestHeader("campaignRunId") int campaignRunId,
                               @RequestHeader("authtoken") String authToken){
        return this.callManager.incomingCall(host,campaignRunId,authToken);
    }


    @PostMapping("/satuts")
    public String status( String status){
        return status;
    }


}
