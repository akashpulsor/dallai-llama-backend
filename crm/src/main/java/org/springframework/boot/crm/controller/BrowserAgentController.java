package org.springframework.boot.crm.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BrowserSession;
import org.springframework.boot.crm.service.BrowserAgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/browser-agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class BrowserAgentController {

    private final BrowserAgentService browserAgentService;

    @PostMapping("/init")
    public ResponseEntity<BrowserSession> initializeSession(@RequestBody BrowserSessionCreateRequest request) throws JsonProcessingException {
        BrowserSession response = browserAgentService.createBrowserSession(request);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/domContext")
    public ResponseEntity<DomContextResponse> analyzeDom(@RequestBody DomContextRequest domContextRequest) {
        log.info("Trying to get dom context: {}", domContextRequest);
        DomContextResponse response = browserAgentService.getDomContext(domContextRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/urlContext")
    public ResponseEntity<GenerateDescriptionResponseDto> analyzeDom(@RequestBody GenerateDescriptionRequestDto generateDescriptionRequestDto) {
        log.info("Trying to get dom context: {}", generateDescriptionRequestDto);
        GenerateDescriptionResponseDto response = browserAgentService.generateContext(generateDescriptionRequestDto);
        return ResponseEntity.ok(response);
    }




    @PostMapping("/validate")
    public ValidateUrlResponseDto validateUrl(@RequestBody ValidateUrlDTO validateUrlDTO) {
        return browserAgentService.validateUrl(validateUrlDTO.getBusinessId(),validateUrlDTO.getUrl());
    }



}
