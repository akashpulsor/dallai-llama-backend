package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.CallLogEvent;
import org.springframework.boot.crm.dto.ChargesDataEvent;
import org.springframework.boot.crm.dto.MakeCallEvent;
import org.springframework.boot.crm.dto.TranscriptionEvent;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.service.CampaignManager;
import org.springframework.boot.crm.service.CampaignService;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@RequestMapping("/api/events")
public class CampaignSseController implements ApplicationListener<ApplicationEvent> {

    private final CampaignManager campaignManager;
    private final Map<Integer, SseEmitter> userEmitters = new ConcurrentHashMap<>();

    public CampaignSseController(CampaignManager campaignManager) {
        this.campaignManager = campaignManager;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/user/{userId}/subscribe")
    public SseEmitter subscribeToUser(@PathVariable int userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        userEmitters.put(userId, emitter);
        emitter.onCompletion(() -> userEmitters.remove(userId));
        emitter.onTimeout(() -> userEmitters.remove(userId));
        emitter.onError((e) -> userEmitters.remove(userId));
        log.info("Client subscribed to userId: {}", userId);
        return emitter;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof CallLogEvent callLogEvent) {
            int campaignRunId = callLogEvent.getCallLog().getCampaignRunId(); // Assuming CallLog has a getUserId() method
            CampaignRunData campaignRunData = this.campaignManager.getCampaignRunDataById(campaignRunId);
            emitEventToUser(campaignRunData.getBusinessId(), "callLogEvent", callLogEvent.getCallLog());
            log.info("CallLogEvent emitted to userId: {}", campaignRunData.getBusinessId());
        } else if (event instanceof ChargesDataEvent chargesDataEvent) {
            int businessId = chargesDataEvent.getChargesData().getBusinessId(); // Assuming ChargesData has a getUserId() method
            emitEventToUser(businessId, "chargesDataEvent", chargesDataEvent.getChargesData());
            log.info("ChargesDataEvent emitted to userId: {}", businessId);
        } else if (event instanceof MakeCallEvent makeCallEvent) {
            int businessId = makeCallEvent.getBusinessId(); // Assuming CallDetails has a getUserId() method
            emitEventToUser(businessId, "makeCallEvent", makeCallEvent.getCampaignRunData());
            log.info("MakeCallEvent emitted to userId: {}", businessId);
        } else if (event instanceof TranscriptionEvent) {
            TranscriptionEvent transcriptionEvent = (TranscriptionEvent) event;
            int businessId = transcriptionEvent.getBusinessId(); // Assuming CallDetails has a getUserId() method
            emitEventToUser(businessId, "transcriptionEvent", transcriptionEvent);
            log.info("TranscriptionEvent emitted to userId: {}", businessId);
        } else {
            log.warn("Unhandled event type: {}", event.getClass().getSimpleName());
        }
    }


    public void emitEventToUser(int userId, String eventName, Object eventData) {
        SseEmitter emitter = userEmitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(eventData));
            } catch (IOException e) {
                log.error("Error sending event '{}' to userId: {}", eventName, userId, e);
                userEmitters.remove(userId);
            }
        }
    }
}