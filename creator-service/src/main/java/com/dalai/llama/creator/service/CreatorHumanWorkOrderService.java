package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorHumanWorkOrder;
import com.dalai.llama.creator.domain.entity.CreatorHumanWorker;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.dto.request.CreateHumanWorkOrderRequest;
import com.dalai.llama.creator.dto.request.HumanWorkOrderMessageRequest;
import com.dalai.llama.creator.dto.request.UpdateHumanWorkOrderRequest;
import com.dalai.llama.creator.dto.request.UpdateHumanWorkerPresenceRequest;
import com.dalai.llama.creator.dto.response.HumanWorkOrderResponse;
import com.dalai.llama.creator.dto.response.HumanWorkerResponse;
import com.dalai.llama.creator.repository.CreatorHumanWorkOrderRepository;
import com.dalai.llama.creator.repository.CreatorHumanWorkerRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreatorHumanWorkOrderService {

    private static final List<String> DEFAULT_QUEUE_STATUSES = List.of("SUBMITTED", "ASSIGNED", "IN_PROGRESS", "DELIVERED", "CHANGE_REQUESTED");
    private static final List<String> ACTIVE_WORKER_STATUSES = List.of("ASSIGNED", "IN_PROGRESS", "CHANGE_REQUESTED");
    private static final int MAX_INCLUDED_CHANGE_REQUESTS = 2;

    private final CreatorHumanWorkOrderRepository workOrderRepository;
    private final CreatorHumanWorkerRepository workerRepository;
    private final CreatorScriptRepository scriptRepository;
    private final ScreenplayVideoService screenplayVideoService;
    private final BillingWalletService billingWalletService;
    private final BigDecimal screenplayReviewFeeInr;
    private final BigDecimal editingJobFeeInr;
    private final BigDecimal extraRevisionFeeInr;

    public CreatorHumanWorkOrderService(
            CreatorHumanWorkOrderRepository workOrderRepository,
            CreatorHumanWorkerRepository workerRepository,
            CreatorScriptRepository scriptRepository,
            ScreenplayVideoService screenplayVideoService,
            BillingWalletService billingWalletService,
            @Value("${creator.human-work-orders.screenplay-review-fee-inr:999}") BigDecimal screenplayReviewFeeInr,
            @Value("${creator.human-work-orders.editing-job-fee-inr:1500}") BigDecimal editingJobFeeInr,
            @Value("${creator.human-work-orders.extra-revision-fee-inr:499}") BigDecimal extraRevisionFeeInr
    ) {
        this.workOrderRepository = workOrderRepository;
        this.workerRepository = workerRepository;
        this.scriptRepository = scriptRepository;
        this.screenplayVideoService = screenplayVideoService;
        this.billingWalletService = billingWalletService;
        this.screenplayReviewFeeInr = defaultAmount(screenplayReviewFeeInr);
        this.editingJobFeeInr = defaultAmount(editingJobFeeInr);
        this.extraRevisionFeeInr = defaultAmount(extraRevisionFeeInr);
    }

    public HumanWorkerContext workerContext(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Worker login is required.");
        }

        Jwt jwt = jwtFrom(authentication);
        String userId = defaultString(authentication.getName(), jwt == null ? "worker" : jwt.getSubject());
        String email = jwt == null ? "" : defaultString(jwt.getClaimAsString("email"), "");
        String displayName = jwt == null
                ? defaultString(userId, "Creative worker")
                : firstText(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"), email, userId);
        String role = resolveWorkerRole(jwt);
        if (role.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Copywriter, editor, or operations role is required.");
        }
        return new HumanWorkerContext(userId, role, displayName, email, "OPS".equals(role));
    }

    @Transactional(readOnly = true)
    public HumanWorkerResponse getWorkerMe(HumanWorkerContext context) {
        CreatorHumanWorker worker = workerRepository.findById(context.userId())
                .orElseGet(() -> CreatorHumanWorker.builder()
                        .userId(context.userId())
                        .role(context.role())
                        .displayName(context.displayName())
                        .email(context.email())
                        .online(false)
                        .active(true)
                        .lastSeenAt(OffsetDateTime.now())
                        .createdAt(OffsetDateTime.now())
                        .updatedAt(OffsetDateTime.now())
                        .build());
        return toWorkerResponse(worker);
    }

    @Transactional
    public HumanWorkerResponse updateWorkerPresence(UpdateHumanWorkerPresenceRequest request, HumanWorkerContext context) {
        UpdateHumanWorkerPresenceRequest safeRequest = request == null
                ? new UpdateHumanWorkerPresenceRequest(null, true, null)
                : request;
        String requestedRole = normalizeWorkerRole(safeRequest.role(), context.role());
        if (!context.ops() && !requestedRole.equals(context.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker cannot change role lane.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        CreatorHumanWorker worker = workerRepository.findById(context.userId())
                .orElseGet(() -> CreatorHumanWorker.builder()
                        .userId(context.userId())
                        .createdAt(now)
                        .build());
        worker.setRole(requestedRole);
        worker.setDisplayName(defaultString(safeRequest.displayName(), context.displayName()));
        worker.setEmail(defaultString(context.email(), worker.getEmail()));
        worker.setOnline(safeRequest.online() == null || safeRequest.online());
        worker.setActive(true);
        worker.setLastSeenAt(now);
        worker.setActiveAssignmentCount((int) workOrderRepository.countByAssignedToAndStatusIn(context.userId(), ACTIVE_WORKER_STATUSES));
        CreatorHumanWorker saved = workerRepository.save(worker);

        if (saved.isOnline() && !"OPS".equals(saved.getRole())) {
            assignPendingForRole(saved.getRole());
            saved.setActiveAssignmentCount((int) workOrderRepository.countByAssignedToAndStatusIn(context.userId(), ACTIVE_WORKER_STATUSES));
            saved = workerRepository.save(saved);
        }
        return toWorkerResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<HumanWorkerResponse> listWorkers(HumanWorkerContext context) {
        requireOps(context);
        return workerRepository
                .findByRoleInOrderByRoleAscOnlineDescLastSeenAtDesc(List.of("COPYWRITER", "EDITOR", "OPS"))
                .stream()
                .map(this::toWorkerResponse)
                .toList();
    }

    @Transactional
    public HumanWorkOrderResponse submit(CreateHumanWorkOrderRequest request, String tenantId, String userId) {
        String safeTenantId = requireText(tenantId, "Tenant id is required.");
        String safeUserId = defaultString(userId, "anonymous");
        CreateHumanWorkOrderRequest safeRequest = request == null
                ? new CreateHumanWorkOrderRequest(null, null, null, null, null, null, null, null, null, null, null)
                : request;
        String workType = normalizeWorkType(safeRequest.workType(), safeRequest.videoRunId());
        CreatorScript script = loadScriptIfPresent(safeRequest.scriptId(), safeTenantId, safeUserId);
        BigDecimal priceAmount = resolvePrice(workType, safeRequest.priceAmount(), safeRequest.videoRunId());
        String priceCurrency = normalizeCurrency(safeRequest.priceCurrency());
        Map<String, Object> sourcePayload = buildSourcePayload(safeRequest, script, safeTenantId, safeUserId, workType, priceAmount, priceCurrency);
        OffsetDateTime now = OffsetDateTime.now();
        CreatorHumanWorkOrder order = CreatorHumanWorkOrder.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(script == null ? null : script.getProjectId())
                .lockedIdeaId(firstNonNull(safeRequest.lockedIdeaId(), script == null ? null : script.getLockedIdeaId()))
                .storyIdeaId(firstNonNull(safeRequest.storyIdeaId(), script == null ? null : script.getStoryIdeaId()))
                .scriptId(script == null ? safeRequest.scriptId() : script.getId())
                .videoRunId(safeRequest.videoRunId())
                .workType(workType)
                .status("SUBMITTED")
                .title(defaultString(safeRequest.title(), defaultTitle(workType, script)))
                .description(defaultString(safeRequest.description(), defaultDescription(workType)))
                .requesterNotes(defaultString(safeRequest.requesterNotes(), ""))
                .sourcePayload(sourcePayload)
                .deliveryPayload(new LinkedHashMap<>())
                .conversation(initialConversation(safeRequest.requesterNotes(), safeUserId))
                .priceAmount(priceAmount)
                .priceCurrency(priceCurrency)
                .billingStatus(priceAmount.signum() > 0 ? "PENDING_APPROVAL" : "NOT_REQUIRED")
                .submittedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        CreatorHumanWorkOrder saved = workOrderRepository.save(order);
        assignRoundRobinIfPossible(saved);
        return toResponse(workOrderRepository.save(saved));
    }

    @Transactional(readOnly = true)
    public List<HumanWorkOrderResponse> listMine(String tenantId, String userId, UUID scriptId, int limit) {
        String safeTenantId = requireText(tenantId, "Tenant id is required.");
        String safeUserId = defaultString(userId, "anonymous");
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        List<CreatorHumanWorkOrder> orders = scriptId == null
                ? workOrderRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(safeTenantId, safeUserId, PageRequest.of(0, safeLimit))
                : workOrderRepository.findByScriptIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(scriptId, safeTenantId, safeUserId);
        return orders.stream().limit(safeLimit).map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public HumanWorkOrderResponse getMine(UUID workOrderId, String tenantId, String userId) {
        return toResponse(loadMine(workOrderId, tenantId, userId));
    }

    @Transactional
    public HumanWorkOrderResponse addCreatorMessage(UUID workOrderId, HumanWorkOrderMessageRequest request, String tenantId, String userId) {
        CreatorHumanWorkOrder order = loadMine(workOrderId, tenantId, userId);
        appendMessage(order, defaultString(userId, "anonymous"), defaultString(request == null ? null : request.authorRole(), "creator"), request == null ? "" : request.message());
        return toResponse(workOrderRepository.save(order));
    }

    @Transactional
    public HumanWorkOrderResponse requestChanges(UUID workOrderId, HumanWorkOrderMessageRequest request, String tenantId, String userId) {
        CreatorHumanWorkOrder order = loadMine(workOrderId, tenantId, userId);
        long previousChangeRequests = countCreatorChangeRequests(order);
        if (previousChangeRequests >= MAX_INCLUDED_CHANGE_REQUESTS) {
            chargeExtraRevision(order, previousChangeRequests + 1);
        }
        order.setStatus("CHANGE_REQUESTED");
        appendMessage(order, defaultString(userId, "anonymous"), "creator", request == null ? "" : request.message(), "CHANGE_REQUEST");
        return toResponse(workOrderRepository.save(order));
    }

    @Transactional
    public HumanWorkOrderResponse approve(UUID workOrderId, UpdateHumanWorkOrderRequest request, String tenantId, String userId) {
        CreatorHumanWorkOrder order = loadMine(workOrderId, tenantId, userId);
        if (!"PAID".equals(order.getBillingStatus()) && defaultAmount(order.getPriceAmount()).signum() > 0) {
            UUID parsedTenantId = parseUuid(order.getTenantId());
            if (parsedTenantId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant id must be a UUID for wallet debit.");
            }
            String billingReference = "HUMAN_WORK_ORDER:" + order.getId() + ":APPROVAL";
            billingWalletService.recordHumanCreativeServiceUsage(
                    parsedTenantId,
                    order.getId(),
                    order.getPriceAmount(),
                    order.getPriceCurrency(),
                    "Human " + labelFor(order.getWorkType()) + " approval",
                    billingReference
            );
            order.setBillingStatus("PAID");
            order.setBillingReference(billingReference);
        }
        if (request != null && request.deliveryPayload() != null && !request.deliveryPayload().isEmpty()) {
            order.setDeliveryPayload(new LinkedHashMap<>(request.deliveryPayload()));
        }
        order.setStatus("APPROVED");
        order.setApprovedAt(OffsetDateTime.now());
        appendSystemMessage(order, "Creator approved the delivered " + labelFor(order.getWorkType()) + ".");
        CreatorHumanWorkOrder saved = workOrderRepository.save(order);
        refreshWorkerAssignmentCount(saved.getAssignedTo());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<HumanWorkOrderResponse> queue(String workType, String status, int limit) {
        List<String> workTypes = filterValues(workType);
        if (workTypes.isEmpty()) {
            workTypes = List.of("SCREENPLAY_REVIEW", "EDITING_JOB");
        }
        List<String> statuses = filterValues(status);
        if (statuses.isEmpty()) {
            statuses = DEFAULT_QUEUE_STATUSES;
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return workOrderRepository
                .findByWorkTypeInAndStatusInOrderBySubmittedAtDesc(workTypes, statuses, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HumanWorkOrderResponse> queue(String workType, String status, int limit, HumanWorkerContext context) {
        List<String> statuses = filterValues(status);
        if (statuses.isEmpty()) {
            statuses = DEFAULT_QUEUE_STATUSES;
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));

        if (context.ops()) {
            List<String> workTypes = filterValues(workType);
            if (workTypes.isEmpty()) {
                workTypes = List.of("SCREENPLAY_REVIEW", "EDITING_JOB");
            }
            return workOrderRepository
                    .findByWorkTypeInAndStatusInOrderBySubmittedAtDesc(workTypes, statuses, PageRequest.of(0, safeLimit))
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        String workerWorkType = workTypeForWorkerRole(context.role());
        if (workerWorkType.isBlank()) {
            return List.of();
        }
        List<String> requestedTypes = filterValues(workType);
        if (!requestedTypes.isEmpty() && !requestedTypes.contains(workerWorkType)) {
            return List.of();
        }
        return workOrderRepository
                .findByWorkTypeInAndStatusInAndAssignedToOrderBySubmittedAtDesc(
                        List.of(workerWorkType),
                        statuses,
                        context.userId(),
                        PageRequest.of(0, safeLimit)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public HumanWorkOrderResponse getForWorker(UUID workOrderId) {
        return toResponse(load(workOrderId));
    }

    @Transactional(readOnly = true)
    public HumanWorkOrderResponse getForWorker(UUID workOrderId, HumanWorkerContext context) {
        return toResponse(loadForWorker(workOrderId, context));
    }

    @Transactional
    public HumanWorkOrderResponse updateForWorker(UUID workOrderId, UpdateHumanWorkOrderRequest request, String workerId) {
        CreatorHumanWorkOrder order = load(workOrderId);
        UpdateHumanWorkOrderRequest safeRequest = request == null
                ? new UpdateHumanWorkOrderRequest(null, null, null, null, null, null)
                : request;
        if (safeRequest.assignedTo() != null && !safeRequest.assignedTo().isBlank()) {
            order.setAssignedTo(safeRequest.assignedTo().trim());
            if (order.getAssignedAt() == null) {
                order.setAssignedAt(OffsetDateTime.now());
            }
        } else if (workerId != null && !workerId.isBlank() && order.getAssignedTo() == null) {
            order.setAssignedTo(workerId);
        }
        if (safeRequest.reviewerNotes() != null) {
            order.setReviewerNotes(safeRequest.reviewerNotes());
        }
        if (safeRequest.requesterNotes() != null) {
            order.setRequesterNotes(safeRequest.requesterNotes());
        }
        if (safeRequest.sourcePayload() != null && !safeRequest.sourcePayload().isEmpty()) {
            Map<String, Object> sourcePayload = new LinkedHashMap<>(order.getSourcePayload() == null ? Map.of() : order.getSourcePayload());
            sourcePayload.putAll(safeRequest.sourcePayload());
            order.setSourcePayload(sourcePayload);
        }
        if (safeRequest.deliveryPayload() != null && !safeRequest.deliveryPayload().isEmpty()) {
            order.setDeliveryPayload(new LinkedHashMap<>(safeRequest.deliveryPayload()));
        }
        String status = normalizeStatus(safeRequest.status());
        if (!status.isBlank()) {
            applyStatus(order, status);
        }
        CreatorHumanWorkOrder saved = workOrderRepository.save(order);
        refreshWorkerAssignmentCount(saved.getAssignedTo());
        return toResponse(saved);
    }

    @Transactional
    public HumanWorkOrderResponse updateForWorker(UUID workOrderId, UpdateHumanWorkOrderRequest request, HumanWorkerContext context) {
        CreatorHumanWorkOrder order = context.ops() ? load(workOrderId) : loadForWorkerOrClaimable(workOrderId, context);
        UpdateHumanWorkOrderRequest safeRequest = request == null
                ? new UpdateHumanWorkOrderRequest(null, null, null, null, null, null)
                : request;
        String previousAssignee = order.getAssignedTo();

        if (context.ops()) {
            if (safeRequest.assignedTo() != null && !safeRequest.assignedTo().isBlank()) {
                order.setAssignedTo(safeRequest.assignedTo().trim());
                if (order.getAssignedAt() == null) {
                    order.setAssignedAt(OffsetDateTime.now());
                }
            }
        } else if (order.getAssignedTo() == null || order.getAssignedTo().isBlank()) {
            order.setAssignedTo(context.userId());
            order.setAssignedAt(OffsetDateTime.now());
        } else if (!Objects.equals(order.getAssignedTo(), context.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found.");
        }

        if (safeRequest.reviewerNotes() != null) {
            order.setReviewerNotes(safeRequest.reviewerNotes());
        }
        if (safeRequest.requesterNotes() != null) {
            order.setRequesterNotes(safeRequest.requesterNotes());
        }
        if (safeRequest.sourcePayload() != null && !safeRequest.sourcePayload().isEmpty()) {
            Map<String, Object> sourcePayload = new LinkedHashMap<>(order.getSourcePayload() == null ? Map.of() : order.getSourcePayload());
            sourcePayload.putAll(safeRequest.sourcePayload());
            order.setSourcePayload(sourcePayload);
        }
        if (safeRequest.deliveryPayload() != null && !safeRequest.deliveryPayload().isEmpty()) {
            order.setDeliveryPayload(new LinkedHashMap<>(safeRequest.deliveryPayload()));
        }
        String status = normalizeStatus(safeRequest.status());
        if (!status.isBlank()) {
            applyStatus(order, status);
        }
        CreatorHumanWorkOrder saved = workOrderRepository.save(order);
        refreshWorkerAssignmentCount(previousAssignee);
        refreshWorkerAssignmentCount(saved.getAssignedTo());
        return toResponse(saved);
    }

    @Transactional
    public HumanWorkOrderResponse addWorkerMessage(UUID workOrderId, HumanWorkOrderMessageRequest request, String workerId) {
        CreatorHumanWorkOrder order = load(workOrderId);
        appendMessage(order, defaultString(workerId, "reviewer"), defaultString(request == null ? null : request.authorRole(), "reviewer"), request == null ? "" : request.message());
        return toResponse(workOrderRepository.save(order));
    }

    @Transactional
    public HumanWorkOrderResponse addWorkerMessage(UUID workOrderId, HumanWorkOrderMessageRequest request, HumanWorkerContext context) {
        CreatorHumanWorkOrder order = loadForWorker(workOrderId, context);
        appendMessage(order, context.userId(), defaultString(request == null ? null : request.authorRole(), context.role().toLowerCase(Locale.ROOT)), request == null ? "" : request.message());
        return toResponse(workOrderRepository.save(order));
    }

    private CreatorHumanWorkOrder loadMine(UUID workOrderId, String tenantId, String userId) {
        String safeTenantId = requireText(tenantId, "Tenant id is required.");
        String safeUserId = defaultString(userId, "anonymous");
        return workOrderRepository.findByIdAndTenantIdAndUserId(workOrderId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found."));
    }

    private CreatorHumanWorkOrder load(UUID workOrderId) {
        return workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found."));
    }

    private CreatorHumanWorkOrder loadForWorker(UUID workOrderId, HumanWorkerContext context) {
        CreatorHumanWorkOrder order = load(workOrderId);
        if (context.ops()) {
            return order;
        }
        String workerWorkType = workTypeForWorkerRole(context.role());
        if (!workerWorkType.equals(order.getWorkType()) || !Objects.equals(order.getAssignedTo(), context.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found.");
        }
        return order;
    }

    private CreatorHumanWorkOrder loadForWorkerOrClaimable(UUID workOrderId, HumanWorkerContext context) {
        CreatorHumanWorkOrder order = load(workOrderId);
        if (context.ops()) {
            return order;
        }
        String workerWorkType = workTypeForWorkerRole(context.role());
        if (!workerWorkType.equals(order.getWorkType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found.");
        }
        if (order.getAssignedTo() == null || order.getAssignedTo().isBlank() || Objects.equals(order.getAssignedTo(), context.userId())) {
            return order;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Human work order not found.");
    }

    private CreatorScript loadScriptIfPresent(UUID scriptId, String tenantId, String userId) {
        if (scriptId == null) {
            return null;
        }
        return scriptRepository.findByIdAndTenantIdAndUserId(scriptId, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay script not found."));
    }

    private Map<String, Object> buildSourcePayload(
            CreateHumanWorkOrderRequest request,
            CreatorScript script,
            String tenantId,
            String userId,
            String workType,
            BigDecimal priceAmount,
            String priceCurrency
    ) {
        Map<String, Object> sourcePayload = new LinkedHashMap<>(request.sourcePayload() == null ? Map.of() : request.sourcePayload());
        sourcePayload.put("workType", workType);
        sourcePayload.put("requestedAt", OffsetDateTime.now().toString());
        sourcePayload.put("priceAmount", priceAmount);
        sourcePayload.put("priceCurrency", priceCurrency);
        if (script != null) {
            sourcePayload.put("screenplay", scriptSnapshot(script));
        }
        if (script != null && request.videoRunId() != null) {
            try {
                Map<String, Object> videoRun = screenplayVideoService.getVideoRun(script.getId(), request.videoRunId(), tenantId, userId);
                Map<String, Object> renderManifest = mapValue(videoRun.get("renderManifest"));
                sourcePayload.put("screenplayVideoRun", videoRun);
                sourcePayload.put("finalClipUrl", firstText(videoRun.get("finalVideoUrl"), videoRun.get("videoUrl"), videoRun.get("downloadUrl")));
                sourcePayload.put("sceneClips", firstNonNull(videoRun.get("sceneClips"), videoRun.get("scenes")));
                sourcePayload.put("segregatedAssets", buildSegregatedAssets(videoRun));
                Object editingPlan = firstNonNull(sourcePayload.get("editingPlan"), videoRun.get("editingPlan"), renderManifest.get("editingPlan"), videoRun.get("finalRenderPlan"), videoRun.get("providerRequest"));
                Map<String, Object> editorHandoffPlan = firstMap(
                        sourcePayload.get("editorHandoffPlan"),
                        videoRun.get("editorHandoffPlan"),
                        renderManifest.get("editorHandoffPlan"),
                        editingPlan
                );
                sourcePayload.put("editingPlan", editingPlan);
                if (!editorHandoffPlan.isEmpty()) {
                    sourcePayload.put("editorHandoffPlan", editorHandoffPlan);
                    sourcePayload.put("recommendedEditingTools", firstNonNull(
                            sourcePayload.get("recommendedEditingTools"),
                            editorHandoffPlan.get("recommendedTools"),
                            editorHandoffPlan.get("toolsToUse"),
                            videoRun.get("recommendedEditingTools"),
                            renderManifest.get("recommendedEditingTools")
                    ));
                    sourcePayload.put("editorChecklist", firstNonNull(
                            sourcePayload.get("editorChecklist"),
                            editorHandoffPlan.get("perShotChecklist"),
                            editorHandoffPlan.get("qualityChecklist")
                    ));
                    sourcePayload.put("editorAssetManifest", firstNonNull(
                            sourcePayload.get("editorAssetManifest"),
                            editorHandoffPlan.get("assetManifest"),
                            buildSegregatedAssets(videoRun)
                    ));
                    sourcePayload.put("musicLicenseRequirements", firstNonNull(
                            sourcePayload.get("musicLicenseRequirements"),
                            editorHandoffPlan.get("musicLicenseRequirements")
                    ));
                }
                sourcePayload.put("imageLedAdPlan", firstNonNull(sourcePayload.get("imageLedAdPlan"), videoRun.get("imageLedAdPlan"), renderManifest.get("imageLedAdPlan")));
                sourcePayload.put("audioProductionPlan", firstNonNull(sourcePayload.get("audioProductionPlan"), videoRun.get("audioProductionPlan"), renderManifest.get("audioProductionPlan")));
                sourcePayload.put("voicePlan", firstNonNull(sourcePayload.get("voicePlan"), videoRun.get("voicePlan"), videoRun.get("audioPlan"), videoRun.get("audioProductionPlan"), videoRun.get("soundDesign")));
                sourcePayload.put("musicPlan", firstNonNull(sourcePayload.get("musicPlan"), videoRun.get("musicPlan"), videoRun.get("backgroundMusicPlan"), renderManifest.get("audioProductionPlan")));
            } catch (RuntimeException ex) {
                sourcePayload.put("videoRunLookupError", ex.getMessage());
            }
        }
        return sourcePayload;
    }

    private Map<String, Object> buildSegregatedAssets(Map<String, Object> videoRun) {
        Map<String, Object> assets = new LinkedHashMap<>();
        assets.put("finalClip", firstText(videoRun.get("finalVideoUrl"), videoRun.get("videoUrl"), videoRun.get("downloadUrl")));
        assets.put("sceneClips", firstNonNull(videoRun.get("sceneClips"), videoRun.get("scenes")));
        assets.put("captions", firstNonNull(videoRun.get("srt"), videoRun.get("srtFile"), videoRun.get("captionTrack")));
        assets.put("voice", firstNonNull(videoRun.get("voiceTrack"), videoRun.get("voiceover"), videoRun.get("dialogueAudio")));
        assets.put("music", firstNonNull(videoRun.get("musicTrack"), videoRun.get("backgroundMusic"), videoRun.get("musicPlan")));
        assets.put("imageAnchors", firstNonNull(videoRun.get("imageLedAdPlan"), mapValue(videoRun.get("renderManifest")).get("imageLedAdPlan")));
        assets.put("assets", firstNonNull(videoRun.get("assets"), videoRun.get("uploadedAssets"), List.of()));
        return assets;
    }

    private void assignPendingForRole(String role) {
        String workType = workTypeForWorkerRole(role);
        if (workType.isBlank()) {
            return;
        }
        List<CreatorHumanWorkOrder> pending = workOrderRepository
                .findByWorkTypeAndStatusAndAssignedToIsNullOrderBySubmittedAtAsc(workType, "SUBMITTED", PageRequest.of(0, 100));
        for (CreatorHumanWorkOrder order : pending) {
            assignRoundRobinIfPossible(order);
            workOrderRepository.save(order);
        }
    }

    private void assignRoundRobinIfPossible(CreatorHumanWorkOrder order) {
        if (order == null || order.getAssignedTo() != null || !"SUBMITTED".equals(order.getStatus())) {
            return;
        }
        String role = workerRoleForWorkType(order.getWorkType());
        if (role.isBlank()) {
            return;
        }
        List<CreatorHumanWorker> workers = workerRepository
                .findByRoleAndOnlineTrueAndActiveTrueOrderByTotalAssignmentCountAscLastAssignedAtAscLastSeenAtAsc(role, PageRequest.of(0, 1));
        if (workers.isEmpty()) {
            return;
        }
        CreatorHumanWorker worker = workers.get(0);
        OffsetDateTime now = OffsetDateTime.now();
        order.setAssignedTo(worker.getUserId());
        order.setAssignedAt(now);
        order.setStatus("ASSIGNED");
        worker.setLastAssignedAt(now);
        worker.setLastSeenAt(now);
        worker.setTotalAssignmentCount(worker.getTotalAssignmentCount() + 1);
        worker.setActiveAssignmentCount((int) workOrderRepository.countByAssignedToAndStatusIn(worker.getUserId(), ACTIVE_WORKER_STATUSES) + 1);
        workerRepository.save(worker);
        appendSystemMessage(order, labelFor(order.getWorkType()) + " assigned to " + defaultString(worker.getDisplayName(), worker.getUserId()) + ".");
    }

    private void refreshWorkerAssignmentCount(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        workerRepository.findById(userId).ifPresent(worker -> {
            worker.setActiveAssignmentCount((int) workOrderRepository.countByAssignedToAndStatusIn(userId, ACTIVE_WORKER_STATUSES));
            workerRepository.save(worker);
        });
    }

    private void chargeExtraRevision(CreatorHumanWorkOrder order, long revisionNumber) {
        if (extraRevisionFeeInr.signum() <= 0) {
            return;
        }
        UUID parsedTenantId = parseUuid(order.getTenantId());
        if (parsedTenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant id must be a UUID for paid revision debit.");
        }
        String billingReference = "HUMAN_WORK_ORDER:" + order.getId() + ":REVISION:" + revisionNumber;
        billingWalletService.recordHumanCreativeServiceUsage(
                parsedTenantId,
                order.getId(),
                extraRevisionFeeInr,
                order.getPriceCurrency(),
                "Paid extra revision for human " + labelFor(order.getWorkType()),
                billingReference
        );
        appendSystemMessage(order, "Paid extra revision accepted for " + labelFor(order.getWorkType()) + ".");
    }

    private Map<String, Object> scriptSnapshot(CreatorScript script) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scriptId", script.getId());
        value.put("projectId", script.getProjectId());
        value.put("lockedIdeaId", script.getLockedIdeaId());
        value.put("storyIdeaId", script.getStoryIdeaId());
        value.put("title", script.getTitle());
        value.put("categoryCode", script.getCategoryCode());
        value.put("durationSeconds", script.getDurationSeconds());
        value.put("dialogueLanguage", script.getDialogueLanguage());
        value.put("screenType", script.getScreenType());
        value.put("scriptText", script.getScriptText());
        value.put("scriptPayload", script.getScriptPayload());
        value.put("shots", script.getShots());
        return value;
    }

    private void applyStatus(CreatorHumanWorkOrder order, String status) {
        order.setStatus(status);
        OffsetDateTime now = OffsetDateTime.now();
        if ("ASSIGNED".equals(status) && order.getAssignedAt() == null) {
            order.setAssignedAt(now);
        }
        if ("IN_PROGRESS".equals(status) && order.getAssignedAt() == null) {
            order.setAssignedAt(now);
        }
        if ("DELIVERED".equals(status)) {
            order.setDeliveredAt(now);
        }
        if ("APPROVED".equals(status)) {
            order.setApprovedAt(now);
        }
        if ("REJECTED".equals(status)) {
            order.setRejectedAt(now);
        }
    }

    private List<Map<String, Object>> initialConversation(String requesterNotes, String userId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (requesterNotes != null && !requesterNotes.isBlank()) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", UUID.randomUUID().toString());
            message.put("authorId", defaultString(userId, "anonymous"));
            message.put("authorRole", "creator");
            message.put("message", requesterNotes);
            message.put("createdAt", OffsetDateTime.now().toString());
            messages.add(message);
        }
        return messages;
    }

    private void appendMessage(CreatorHumanWorkOrder order, String authorId, String authorRole, String text) {
        appendMessage(order, authorId, authorRole, text, null);
    }

    private void appendMessage(CreatorHumanWorkOrder order, String authorId, String authorRole, String text, String messageType) {
        String messageText = defaultString(text, "").trim();
        if (messageText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required.");
        }
        List<Map<String, Object>> conversation = new ArrayList<>(order.getConversation() == null ? List.of() : order.getConversation());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("authorId", authorId);
        message.put("authorRole", authorRole);
        message.put("message", messageText);
        if (messageType != null && !messageType.isBlank()) {
            message.put("messageType", messageType);
        }
        message.put("createdAt", OffsetDateTime.now().toString());
        conversation.add(message);
        order.setConversation(conversation);
    }

    private long countCreatorChangeRequests(CreatorHumanWorkOrder order) {
        return (order.getConversation() == null ? List.<Map<String, Object>>of() : order.getConversation()).stream()
                .filter(message -> "creator".equalsIgnoreCase(String.valueOf(message.get("authorRole"))))
                .filter(message -> "CHANGE_REQUEST".equalsIgnoreCase(String.valueOf(message.get("messageType"))))
                .count();
    }

    private void appendSystemMessage(CreatorHumanWorkOrder order, String text) {
        List<Map<String, Object>> conversation = new ArrayList<>(order.getConversation() == null ? List.of() : order.getConversation());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("authorId", "system");
        message.put("authorRole", "system");
        message.put("message", text);
        message.put("createdAt", OffsetDateTime.now().toString());
        conversation.add(message);
        order.setConversation(conversation);
    }

    private HumanWorkOrderResponse toResponse(CreatorHumanWorkOrder order) {
        return new HumanWorkOrderResponse(
                order.getId(),
                order.getTenantId(),
                order.getUserId(),
                order.getProjectId(),
                order.getLockedIdeaId(),
                order.getStoryIdeaId(),
                order.getScriptId(),
                order.getVideoRunId(),
                order.getWorkType(),
                order.getStatus(),
                order.getTitle(),
                order.getDescription(),
                order.getRequesterNotes(),
                order.getReviewerNotes(),
                order.getAssignedTo(),
                order.getSourcePayload(),
                order.getDeliveryPayload(),
                order.getConversation(),
                order.getPriceAmount(),
                order.getPriceCurrency(),
                order.getBillingStatus(),
                order.getBillingReference(),
                order.getSubmittedAt(),
                order.getAssignedAt(),
                order.getDeliveredAt(),
                order.getApprovedAt(),
                order.getRejectedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private HumanWorkerResponse toWorkerResponse(CreatorHumanWorker worker) {
        return new HumanWorkerResponse(
                worker.getUserId(),
                worker.getRole(),
                worker.getDisplayName(),
                worker.getEmail(),
                worker.isOnline(),
                worker.isActive(),
                worker.getActiveAssignmentCount(),
                worker.getTotalAssignmentCount(),
                worker.getLastAssignedAt(),
                worker.getLastSeenAt(),
                worker.getCreatedAt(),
                worker.getUpdatedAt()
        );
    }

    private String normalizeWorkType(String value, UUID videoRunId) {
        String normalized = defaultString(value, videoRunId == null ? "SCREENPLAY_REVIEW" : "EDITING_JOB")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (normalized.contains("EDIT")) {
            return "EDITING_JOB";
        }
        return "SCREENPLAY_REVIEW";
    }

    private String normalizeStatus(String value) {
        String normalized = defaultString(value, "").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "SUBMITTED", "ASSIGNED", "IN_PROGRESS", "DELIVERED", "CHANGE_REQUESTED", "APPROVED", "REJECTED" -> normalized;
            default -> "";
        };
    }

    private String normalizeWorkerRole(String value, String fallback) {
        String normalized = defaultString(value, fallback).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.contains("OPS") || normalized.contains("OPERATION")) {
            return "OPS";
        }
        if (normalized.contains("EDITOR")) {
            return "EDITOR";
        }
        if (normalized.contains("COPYWRITER") || normalized.contains("WRITER")) {
            return "COPYWRITER";
        }
        return defaultString(fallback, "COPYWRITER");
    }

    private String workerRoleForWorkType(String workType) {
        return "EDITING_JOB".equals(workType) ? "EDITOR" : "SCREENPLAY_REVIEW".equals(workType) ? "COPYWRITER" : "";
    }

    private String workTypeForWorkerRole(String role) {
        return "EDITOR".equals(role) ? "EDITING_JOB" : "COPYWRITER".equals(role) ? "SCREENPLAY_REVIEW" : "";
    }

    private void requireOps(HumanWorkerContext context) {
        if (context == null || !context.ops()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operations role is required.");
        }
    }

    private Jwt jwtFrom(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }

    private String resolveWorkerRole(Jwt jwt) {
        if (jwt == null) {
            return "";
        }
        List<String> roles = new ArrayList<>();
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> realmMap) {
            Object realmRoles = realmMap.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                collection.forEach(role -> roles.add(String.valueOf(role).toLowerCase(Locale.ROOT)));
            }
        }
        Object resourceAccess = jwt.getClaims().get("resource_access");
        if (resourceAccess instanceof Map<?, ?> resourceMap) {
            for (Object value : resourceMap.values()) {
                if (value instanceof Map<?, ?> clientMap) {
                    Object clientRoles = clientMap.get("roles");
                    if (clientRoles instanceof Collection<?> collection) {
                        collection.forEach(role -> roles.add(String.valueOf(role).toLowerCase(Locale.ROOT)));
                    }
                }
            }
        }
        if (roles.stream().anyMatch(role -> role.equals("admin") || role.equals("creator_ops") || role.equals("operations") || role.equals("ops"))) {
            return "OPS";
        }
        if (roles.stream().anyMatch(role -> role.equals("creator_editor") || role.equals("editor"))) {
            return "EDITOR";
        }
        if (roles.stream().anyMatch(role -> role.equals("creator_copywriter") || role.equals("copywriter"))) {
            return "COPYWRITER";
        }
        String clientId = defaultString(jwt.getClaimAsString("azp"), "").toLowerCase(Locale.ROOT);
        if ("operations-ui".equals(clientId) || "ops-ui".equals(clientId)) {
            return "OPS";
        }
        if ("editor-ui".equals(clientId)) {
            return "EDITOR";
        }
        if ("copywriter-ui".equals(clientId)) {
            return "COPYWRITER";
        }
        return "";
    }

    private List<String> filterValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(item -> item.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private BigDecimal resolvePrice(String workType, BigDecimal requestedPrice, UUID videoRunId) {
        if ("EDITING_JOB".equals(workType) && videoRunId != null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal value = defaultAmount(requestedPrice);
        if (value.signum() > 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal defaultValue = "EDITING_JOB".equals(workType) ? editingJobFeeInr : screenplayReviewFeeInr;
        return defaultAmount(defaultValue).setScale(4, RoundingMode.HALF_UP);
    }

    private String defaultTitle(String workType, CreatorScript script) {
        String base = script == null ? "creator project" : defaultString(script.getTitle(), "screenplay");
        return "EDITING_JOB".equals(workType) ? "Freelancer edit: " + base : "Human screenplay review: " + base;
    }

    private String defaultDescription(String workType) {
        return "EDITING_JOB".equals(workType)
                ? "Edit the final generated video using the supplied final clip, scene clips, voice, music, captions, storyboard, and editing plan."
                : "Review and improve the screenplay using human creative judgment before video generation.";
    }

    private String labelFor(String workType) {
        return "EDITING_JOB".equals(workType) ? "editing job" : "screenplay review";
    }

    private String normalizeCurrency(String value) {
        String normalized = defaultString(value, "INR").trim().toUpperCase(Locale.ROOT);
        return normalized.length() == 3 ? normalized : "INR";
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = defaultString(value, "").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private Map<String, Object> firstMap(Object... values) {
        if (values == null) {
            return new LinkedHashMap<>();
        }
        for (Object value : values) {
            Map<String, Object> map = mapValue(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new LinkedHashMap<>();
    }

    private BigDecimal defaultAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private String defaultString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record HumanWorkerContext(
            String userId,
            String role,
            String displayName,
            String email,
            boolean ops
    ) {
    }
}
