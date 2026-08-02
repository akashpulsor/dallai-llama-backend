package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.TimelineIngestionSessionRequest;
import com.dalai.llama.creator.dto.response.TimelineIngestionResponse;
import com.dalai.llama.creator.service.CreatorTimelineIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/shorts/timeline-ingestions")
public class CreatorTimelineIngestionController {

    private static final Logger log = LoggerFactory.getLogger(CreatorTimelineIngestionController.class);

    private final CreatorTimelineIngestionService timelineIngestionService;

    public CreatorTimelineIngestionController(CreatorTimelineIngestionService timelineIngestionService) {
        this.timelineIngestionService = timelineIngestionService;
    }

    @PostMapping
    public ResponseEntity<TimelineIngestionResponse> createSession(
            @RequestBody(required = false) TimelineIngestionSessionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info("Timeline ingestion session requested tenantId={} userId={} title={}", tenantId, userId, request == null ? "" : request.title());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(timelineIngestionService.createSession(request, tenantId, userId));
    }

    @PostMapping(value = "/{videoId}/parts/{partNumber}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TimelineIngestionResponse> uploadPart(
            @PathVariable UUID videoId,
            @PathVariable int partNumber,
            @RequestParam("part") MultipartFile part,
            @RequestParam(value = "partStartSeconds", required = false) Double partStartSeconds,
            @RequestParam(value = "partDurationSeconds", required = false) Double partDurationSeconds,
            @RequestParam(value = "finalPart", required = false) Boolean finalPart,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info(
                "Timeline ingestion part received videoId={} partNumber={} tenantId={} userId={} contentType={} sizeBytes={} startSeconds={} durationSeconds={} finalPart={}",
                videoId,
                partNumber,
                tenantId,
                userId,
                part == null ? "" : part.getContentType(),
                part == null ? 0L : part.getSize(),
                partStartSeconds,
                partDurationSeconds,
                finalPart
        );
        return ResponseEntity.ok(timelineIngestionService.uploadPart(
                videoId,
                partNumber,
                part,
                partStartSeconds,
                partDurationSeconds,
                Boolean.TRUE.equals(finalPart),
                tenantId,
                userId
        ));
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<TimelineIngestionResponse> getSession(
            @PathVariable UUID videoId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.getSession(videoId, tenantId, userId));
    }

    @PostMapping("/{videoId}/complete")
    public ResponseEntity<TimelineIngestionResponse> completeSession(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.completeSession(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/fabric-timeline")
    public ResponseEntity<TimelineIngestionResponse> requestFabricTimeline(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.requestFabricTimeline(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/transcript-timeline")
    public ResponseEntity<TimelineIngestionResponse> requestTranscriptTimeline(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.requestTranscriptTimeline(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/video-analysis")
    public ResponseEntity<TimelineIngestionResponse> requestVideoAnalysis(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.requestVideoAnalysis(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/story-shots")
    public ResponseEntity<TimelineIngestionResponse> requestStoryShots(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(timelineIngestionService.requestStoryShots(videoId, payload, tenantId, userId));
    }

    private String resolveUserId(Authentication authentication, String header) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        return "demo-user";
    }
}
