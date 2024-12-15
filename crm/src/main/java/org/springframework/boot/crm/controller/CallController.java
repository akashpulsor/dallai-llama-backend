package org.springframework.boot.crm.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.service.CallManager;
import org.springframework.boot.crm.service.MetaManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/call")
public class CallController {

    private final CallManager callManager;

    public CallController(CallManager callManager) {
        this.callManager = callManager;
    }
}
