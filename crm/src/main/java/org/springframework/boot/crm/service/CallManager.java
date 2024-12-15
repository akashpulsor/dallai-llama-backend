package org.springframework.boot.crm.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
@Component
public class CallManager {

    private ConcurrentHashMap<String,RealTimeSession> twilioOpenAiMap;

    private final ApplicationEventPublisher applicationEventPublisher;

    private final ToolsService toolsService;

    private final CallLogService callLogService;

    private final BusinessApiKeyService businessApiKeyService;

    private final AgentManager agentManager;

    private final LeadManager leadManager;


    private final BusinessManager businessManager;

    public CallManager(ApplicationEventPublisher applicationEventPublisher, ToolsService toolsService,
                        CallLogService callLogService, BusinessApiKeyService businessApiKeyService, AgentManager agentManager,
                        LeadManager leadManager,  BusinessManager businessManager) {
        this.applicationEventPublisher = applicationEventPublisher;
        twilioOpenAiMap = new ConcurrentHashMap<>();
        this.toolsService = toolsService;
        this.callLogService = callLogService;
        this.businessApiKeyService=businessApiKeyService;
        this.agentManager = agentManager;
        this.leadManager = leadManager;

        this.businessManager = businessManager;
    }



}
