package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortCandidate;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorShortCandidateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ShortRenderingService {

    private static final Logger log = LoggerFactory.getLogger(ShortRenderingService.class);
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final String ASSET_TYPE_RENDERED_SHORT = "SHORT_RENDERED_VIDEO";
    private static final String DEFAULT_CAPTION_FONT = "Noto Sans";

    private final AssetStorageService assetStorageService;
    private final CreatorAssetRepository assetRepository;
    private final CreatorShortCandidateRepository candidateRepository;
    private final int maxRenderedCandidates;
    private final Duration ffmpegTimeout;

    public ShortRenderingService(
            AssetStorageService assetStorageService,
            CreatorAssetRepository assetRepository,
            CreatorShortCandidateRepository candidateRepository,
            @Value("${creator.shorts.render.max-candidates:3}") int maxRenderedCandidates,
            @Value("${creator.shorts.render.ffmpeg-timeout-minutes:30}") long ffmpegTimeoutMinutes
    ) {
        this.assetStorageService = assetStorageService;
        this.assetRepository = assetRepository;
        this.candidateRepository = candidateRepository;
        this.maxRenderedCandidates = Math.max(1, maxRenderedCandidates);
        this.ffmpegTimeout = Duration.ofMinutes(Math.max(1, ffmpegTimeoutMinutes));
    }

    public RenderedShortsResult render(CreatorShortVideo video, CreatorAsset sourceAsset, List<CreatorShortCandidate> candidates) {
        return render(video, sourceAsset, candidates, null);
    }

    public RenderedShortsResult render(CreatorShortVideo video, CreatorAsset sourceAsset, List<CreatorShortCandidate> candidates, Path sourceVideoPath) {
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> renderedCandidates = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        if (video == null || sourceAsset == null || candidates == null || candidates.isEmpty()) {
            addTrace(trace, "RENDERING", "SKIPPED", "No short candidates were available for rendering.", 0.0, Map.of());
            return new RenderedShortsResult(renderedCandidates, trace, Map.of(
                    "status", "SKIPPED",
                    "renderedCount", 0,
                    "failedCount", 0
            ));
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-short-render-");
            boolean reuseSource = reusableSourcePath(sourceVideoPath);
            Path sourcePath = reuseSource
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey(), ".mp4"));
            if (!reuseSource) {
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
            }
            boolean sourceHasAudio = hasAudio(sourcePath);
            List<CreatorShortCandidate> candidatesToRender = candidates.stream()
                    .limit(maxRenderedCandidates)
                    .toList();
            List<Map<String, Object>> deferredCandidates = deferredCandidates(candidates, candidatesToRender.size());

            for (CreatorShortCandidate candidate : candidatesToRender) {
                try {
                    if (renderBlockedByGlobalCritic(candidate)) {
                        Map<String, Object> failure = globalCriticBlock(candidate);
                        failures.add(failure);
                        markRenderBlocked(candidate, failure);
                        continue;
                    }
                    markRenderStarted(video, candidate);
                    RenderedCandidate rendered = renderCandidate(video, sourceAsset, candidate, sourcePath, sourceHasAudio, workspace);
                    renderedCandidates.add(rendered.summary());
                } catch (RuntimeException ex) {
                    log.warn("Short rendering failed videoId={} candidateId={} rank={} error={}",
                            video.getId(), candidate.getId(), candidate.getRankIndex(), ex.getMessage(), ex);
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("candidateId", candidate.getId() == null ? null : candidate.getId().toString());
                    failure.put("rankIndex", candidate.getRankIndex());
                    failure.put("message", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                    failures.add(failure);
                    markRenderFailed(candidate, failure);
                } finally {
                    deleteQuietly(candidateWorkspace(workspace, candidate));
                }
            }
            if (!deferredCandidates.isEmpty()) {
                log.info(
                        "Short rendering deferred candidates videoId={} renderedLimit={} deferredCount={}",
                        video.getId(),
                        maxRenderedCandidates,
                        deferredCandidates.size()
                );
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create short rendering workspace.", ex);
        } finally {
            deleteQuietly(workspace);
        }

        int deferredCount = Math.max(0, candidates.size() - Math.min(candidates.size(), maxRenderedCandidates));
        String status = failures.isEmpty()
                ? (deferredCount > 0 ? "COMPLETED_WITH_WARNINGS" : "COMPLETED")
                : (renderedCandidates.isEmpty() && deferredCount == 0 ? "FAILED" : "COMPLETED_WITH_WARNINGS");
        addTrace(trace, "RENDERING", status, renderSummary(status, renderedCandidates.size(), failures.size(), deferredCount),
                failures.isEmpty() ? 0.92 : 0.62,
                Map.of(
                        "renderedCount", renderedCandidates.size(),
                        "failedCount", failures.size(),
                        "deferredCount", deferredCount,
                        "maxRenderedCandidates", maxRenderedCandidates,
                        "renderer", "local_ffmpeg",
                        "renderedCandidates", renderedCandidates,
                        "failures", failures,
                        "deferredCandidates", deferredCandidates(candidates, Math.min(candidates.size(), maxRenderedCandidates))
                ));
        addTrace(trace, "COMPLETED", "COMPLETED", "Generate Shorts pipeline finished with render artifacts persisted to MinIO.",
                failures.isEmpty() ? 0.94 : 0.72,
                Map.of("renderingStatus", status));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        metadata.put("renderedCount", renderedCandidates.size());
        metadata.put("failedCount", failures.size());
        metadata.put("deferredCount", deferredCount);
        metadata.put("maxRenderedCandidates", maxRenderedCandidates);
        metadata.put("renderer", "local_ffmpeg");
        metadata.put("renderedCandidates", renderedCandidates);
        metadata.put("failures", failures);
        metadata.put("deferredCandidates", deferredCandidates(candidates, Math.min(candidates.size(), maxRenderedCandidates)));
        metadata.put("renderedAt", OffsetDateTime.now().toString());
        return new RenderedShortsResult(renderedCandidates, trace, metadata);
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
    }

    private Path candidateWorkspace(Path workspace, CreatorShortCandidate candidate) {
        return workspace.resolve("candidate-" + (candidate == null ? "unknown" : String.valueOf(candidate.getId())));
    }

    private List<Map<String, Object>> deferredCandidates(List<CreatorShortCandidate> candidates, int renderedLimit) {
        if (candidates == null || candidates.size() <= renderedLimit) {
            return List.of();
        }
        List<Map<String, Object>> deferred = new ArrayList<>();
        for (int index = renderedLimit; index < candidates.size(); index++) {
            CreatorShortCandidate candidate = candidates.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", candidate == null || candidate.getId() == null ? null : candidate.getId().toString());
            item.put("rankIndex", candidate == null ? null : candidate.getRankIndex());
            item.put("status", candidate == null ? "" : candidate.getStatus());
            item.put("reason", "Deferred by creator.shorts.render.max-candidates to keep render/QA inside pod resource limits.");
            deferred.add(item);
        }
        return deferred;
    }

    private void markRenderStarted(CreatorShortVideo video, CreatorShortCandidate candidate) {
        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        Map<String, Object> metadata = mapValue(candidate.getMetadata());
        String startedAt = OffsetDateTime.now().toString();
        renderManifest.put("renderStatus", "RENDERING");
        renderManifest.put("renderer", "local_ffmpeg");
        renderManifest.put("renderStartedAt", startedAt);
        metadata.put("renderingStatus", "RENDERING");
        metadata.put("renderStartedAt", startedAt);
        candidate.setStatus("RENDERING");
        candidate.setRenderManifest(renderManifest);
        candidate.setMetadata(metadata);
        candidateRepository.saveAndFlush(candidate);
        log.info(
                "Short rendering started videoId={} candidateId={} rank={} title={}",
                video == null ? null : video.getId(),
                candidate.getId(),
                candidate.getRankIndex(),
                candidate.getTitle()
        );
    }

    private RenderedCandidate renderCandidate(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            CreatorShortCandidate candidate,
            Path sourcePath,
            boolean sourceHasAudio,
            Path workspace
    ) {
        List<Map<String, Object>> segments = listOfMaps(mapValue(candidate.getEditDecisionList()).get("segments"));
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Short candidate has no renderable EDL segments.");
        }

        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        Map<String, Object> visualPlan = mapValue(renderManifest.get("visualEnhancementPlan"));
        Map<String, Object> candidateMetadata = mapValue(candidate.getMetadata());
        List<Map<String, Object>> manualRetentionPlan = listOfMaps(firstNonEmpty(
                renderManifest.get("manualRetentionPlan"),
                renderManifest.get("retentionPlan"),
                candidateMetadata.get("manualRetentionPlan")
        ));
        Dimensions target = targetDimensions(stringValue(firstNonEmpty(visualPlan.get("aspectRatio"), renderManifest.get("aspectRatio")), aspectRatioFor(video)));
        Path candidateDir = candidateWorkspace(workspace, candidate);
        try {
            Files.createDirectories(candidateDir);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create candidate render workspace.", ex);
        }

        List<Path> normalizedSegments = new ArrayList<>();
        List<Map<String, Object>> renderedSegments = new ArrayList<>();
        double timelineCursor = 0.0;
        for (int index = 0; index < segments.size(); index++) {
            Map<String, Object> segment = segments.get(index);
            double sourceStart = Math.max(0.0, doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0)));
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), sourceStart + 0.1)));
            double duration = Math.max(0.1, sourceEnd - sourceStart);
            double timelineStart = doubleValue(segment.get("timelineStart"), timelineCursor);
            double timelineEnd = Math.max(timelineStart + 0.1, doubleValue(segment.get("timelineEnd"), timelineStart + duration));
            timelineCursor = timelineEnd;
            Path output = candidateDir.resolve("segment-%03d.mp4".formatted(index));
            List<Map<String, Object>> retentionEffects = retentionEffectsForWindow(manualRetentionPlan, timelineStart, timelineEnd);
            Map<String, Object> segmentVisualPlan = segmentVisualPlan(visualPlan, index, segment, retentionEffects);
            normalizeSegment(sourcePath, output, target, sourceStart, sourceEnd, sourceHasAudio, visualPlan, segmentVisualPlan);
            normalizedSegments.add(output);
            Map<String, Object> segmentMetadata = new LinkedHashMap<>(segment);
            segmentMetadata.put("renderedSegmentFile", output.getFileName().toString());
            segmentMetadata.put("renderedDurationSeconds", round3(sourceEnd - sourceStart));
            segmentMetadata.put("timelineStart", round3(timelineStart));
            segmentMetadata.put("timelineEnd", round3(timelineEnd));
            segmentMetadata.put("visualEnhancementApplied", !visualPlan.isEmpty());
            segmentMetadata.put("visualEnhancementProfile", stringValue(firstNonEmpty(segmentVisualPlan.get("filterProfile"), visualPlan.get("filterProfile")), "balanced_social"));
            segmentMetadata.put("visualEnhancementLayout", stringValue(firstNonEmpty(segmentVisualPlan.get("layoutMode"), visualPlan.get("layoutMode")), "fit_pad"));
            if (!retentionEffects.isEmpty()) {
                segmentMetadata.put("manualRetentionEffects", retentionEffects);
            }
            renderedSegments.add(segmentMetadata);
        }

        Path concatFile = candidateDir.resolve("concat.txt");
        Path concatenated = candidateDir.resolve("concat.mp4");
        try {
            Files.writeString(concatFile, concatFile(normalizedSegments));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare concat file for short rendering.", ex);
        }
        run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                concatFile.toString(),
                "-c",
                "copy",
                "-movflags",
                "+faststart",
                concatenated.toString()
        ), candidateDir.resolve("concat.log"), "FFmpeg short concat failed");

        Path assFile = candidateDir.resolve("captions.ass");
        List<Map<String, Object>> renderCaptions = captionsForRender(candidate, segments);
        renderCaptions = withRetentionOverlayCaptions(renderCaptions, manualRetentionPlan);
        Map<String, Object> captionPlanForRender = mapValue(candidate.getCaptionPlan());
        captionPlanForRender.put("captions", renderCaptions);
        captionPlanForRender.put("source", renderCaptions.isEmpty() ? "empty" : "final_edl_transcript");
        captionPlanForRender.put("renderAligned", true);
        captionPlanForRender.put("renderAlignedAt", OffsetDateTime.now().toString());
        writeCaptions(candidate, target, assFile, visualPlan, renderCaptions);
        Path finalPath = candidateDir.resolve("rendered-short.mp4");
        applyCaptions(concatenated, assFile, finalPath, candidateDir.resolve("captions.log"));

        String objectKey = "shorts/%s/%s/rendered/%s/%s-r%02d.mp4".formatted(
                safePath(video.getTenantId()),
                DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now()),
                video.getId(),
                candidate.getId(),
                candidate.getRankIndex() == null ? 0 : candidate.getRankIndex()
        );
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(objectKey, finalPath, "video/mp4", SIGNED_URL_TTL);

        Map<String, Object> assetMetadata = new LinkedHashMap<>();
        assetMetadata.put("shortVideoId", video.getId().toString());
        assetMetadata.put("sourceAssetId", sourceAsset.getId().toString());
        assetMetadata.put("candidateId", candidate.getId().toString());
        assetMetadata.put("rankIndex", candidate.getRankIndex());
        assetMetadata.put("renderer", "local_ffmpeg");
        assetMetadata.put("targetWidth", target.width());
        assetMetadata.put("targetHeight", target.height());
        assetMetadata.put("aspectRatio", stringValue(renderManifest.get("aspectRatio"), aspectRatioFor(video)));
        assetMetadata.put("renderedSegments", renderedSegments);
        assetMetadata.put("captionPlan", captionPlanForRender);
        assetMetadata.put("renderCaptions", renderCaptions);
        assetMetadata.put("visualEnhancementApplied", !visualPlan.isEmpty());
        assetMetadata.put("visualEnhancementPlan", visualPlan);
        assetMetadata.put("manualRetentionApplied", !manualRetentionPlan.isEmpty());
        assetMetadata.put("manualRetentionPlan", manualRetentionPlan);
        assetMetadata.put("renderedAt", OffsetDateTime.now().toString());
        assetMetadata.put("ffmpegConcatLogTail", tail(readQuietly(candidateDir.resolve("concat.log")), 1600));
        assetMetadata.put("ffmpegCaptionLogTail", tail(readQuietly(candidateDir.resolve("captions.log")), 1600));

        CreatorAsset renderedAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(video.getTenantId())
                .userId(video.getUserId())
                .projectId(video.getProjectId())
                .assetType(ASSET_TYPE_RENDERED_SHORT)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(assetMetadata)
                .build());

        Map<String, Object> updatedManifest = mapValue(candidate.getRenderManifest());
        updatedManifest.put("renderStatus", "RENDERED");
        updatedManifest.put("renderer", "local_ffmpeg");
        updatedManifest.put("assetId", renderedAsset.getId().toString());
        updatedManifest.put("bucket", stored.bucket());
        updatedManifest.put("objectKey", stored.objectKey());
        updatedManifest.put("contentType", stored.contentType());
        updatedManifest.put("publicUrl", stored.signedUrl());
        updatedManifest.put("sizeBytes", stored.sizeBytes());
        updatedManifest.put("targetWidth", target.width());
        updatedManifest.put("targetHeight", target.height());
        updatedManifest.put("renderedAt", OffsetDateTime.now().toString());
        updatedManifest.put("renderedSegments", renderedSegments);
        updatedManifest.put("renderCaptions", renderCaptions);
        updatedManifest.put("visualEnhancementStatus", visualPlan.isEmpty() ? "MISSING" : "APPLIED");
        updatedManifest.put("visualEnhancementPlan", visualPlan);
        updatedManifest.put("manualRetentionApplied", !manualRetentionPlan.isEmpty());
        updatedManifest.put("manualRetentionPlan", manualRetentionPlan);

        Map<String, Object> metadata = mapValue(candidate.getMetadata());
        metadata.put("renderedAssetId", renderedAsset.getId().toString());
        metadata.put("renderedAssetUrl", stored.signedUrl());
        metadata.put("renderingStatus", "RENDERED");
        metadata.put("visualEnhancementApplied", !visualPlan.isEmpty());
        metadata.put("visualEnhancementProfile", stringValue(visualPlan.get("filterProfile"), ""));
        metadata.put("manualRetentionApplied", !manualRetentionPlan.isEmpty());

        candidate.setAssetId(renderedAsset.getId());
        candidate.setStatus("RENDERED");
        candidate.setCaptionPlan(captionPlanForRender);
        candidate.setRenderManifest(updatedManifest);
        candidate.setMetadata(metadata);
        candidateRepository.save(candidate);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("candidateId", candidate.getId().toString());
        summary.put("assetId", renderedAsset.getId().toString());
        summary.put("rankIndex", candidate.getRankIndex());
        summary.put("title", candidate.getTitle());
        summary.put("status", candidate.getStatus());
        summary.put("bucket", stored.bucket());
        summary.put("objectKey", stored.objectKey());
        summary.put("publicUrl", stored.signedUrl());
        summary.put("sizeBytes", stored.sizeBytes());
        summary.put("targetWidth", target.width());
        summary.put("targetHeight", target.height());
        return new RenderedCandidate(summary);
    }

    private void normalizeSegment(
            Path sourcePath,
            Path output,
            Dimensions target,
            double sourceStart,
            double sourceEnd,
            boolean sourceHasAudio,
            Map<String, Object> visualPlan,
            Map<String, Object> segmentVisualPlan
    ) {
        double duration = Math.max(0.1, sourceEnd - sourceStart);
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-nostdin");
        command.add("-y");
        command.add("-ss");
        command.add(formatSeconds(sourceStart));
        command.add("-t");
        command.add(formatSeconds(duration));
        command.add("-i");
        command.add(sourcePath.toString());
        if (!sourceHasAudio) {
            command.add("-f");
            command.add("lavfi");
            command.add("-t");
            command.add(formatSeconds(duration));
            command.add("-i");
            command.add("anullsrc=channel_layout=stereo:sample_rate=48000");
        }
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add(sourceHasAudio ? "0:a?" : "1:a:0");
        command.add("-filter_threads");
        command.add("1");
        command.add("-vf");
        command.add(videoFilter(target, visualPlan, segmentVisualPlan));
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("20");
        command.add("-threads");
        command.add("1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("160k");
        command.add("-ar");
        command.add("48000");
        command.add("-ac");
        command.add("2");
        command.add("-shortest");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        run(command, output.resolveSibling(output.getFileName() + ".log"), "FFmpeg short segment normalization failed");
    }

    private List<Map<String, Object>> captionsForRender(CreatorShortCandidate candidate, List<Map<String, Object>> segments) {
        List<Map<String, Object>> timelineCaptions = captionsFromTimelineSegments(segments);
        if (!timelineCaptions.isEmpty()) {
            return timelineCaptions;
        }
        return listOfMaps(mapValue(candidate.getCaptionPlan()).get("captions"));
    }

    private List<Map<String, Object>> captionsFromTimelineSegments(List<Map<String, Object>> segments) {
        List<Map<String, Object>> captions = new ArrayList<>();
        double timelineCursor = 0.0;
        int captionIndex = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            Map<String, Object> segment = segments.get(segmentIndex);
            double sourceStart = doubleValue(firstNonEmpty(segment.get("sourceStart"), segment.get("start")), 0.0);
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(firstNonEmpty(segment.get("sourceEnd"), segment.get("end")), sourceStart + 0.1));
            double duration = Math.max(0.1, sourceEnd - sourceStart);
            double timelineStart = doubleValue(segment.get("timelineStart"), timelineCursor);
            double timelineEnd = Math.max(timelineStart + 0.1, doubleValue(segment.get("timelineEnd"), timelineStart + duration));
            timelineCursor = timelineEnd;

            String transcript = segmentTranscript(segment);
            if (transcript.isBlank()) {
                continue;
            }
            List<String> chunks = splitCaptionText(transcript, 64);
            double chunkDuration = Math.max(0.8, (timelineEnd - timelineStart) / Math.max(1, chunks.size()));
            for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                double start = timelineStart + (chunkDuration * chunkIndex);
                double end = chunkIndex == chunks.size() - 1 ? timelineEnd : Math.min(timelineEnd, start + chunkDuration);
                Map<String, Object> caption = new LinkedHashMap<>();
                caption.put("id", "timeline-caption-%03d".formatted(captionIndex++));
                caption.put("segmentId", stringValue(firstNonEmpty(segment.get("segmentId"), segment.get("id")), "segment-" + segmentIndex));
                caption.put("nodeId", stringValue(segment.get("nodeId"), ""));
                caption.put("sceneId", stringValue(segment.get("sceneId"), ""));
                caption.put("start", round3(start));
                caption.put("end", round3(Math.max(start + 0.5, end)));
                caption.put("text", chunks.get(chunkIndex));
                caption.put("source", "final_edl_transcript");
                captions.add(caption);
            }
        }
        return captions;
    }

    private String segmentTranscript(Map<String, Object> segment) {
        String text = firstTextValue(segment,
                "transcript",
                "text",
                "voice",
                "dialogue",
                "sourceTranscript",
                "caption",
                "summary"
        );
        if (!text.isBlank()) {
            return text;
        }
        List<String> nested = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(firstNonEmpty(segment.get("transcriptNodes"), segment.get("nodes"), segment.get("captions")))) {
            String itemText = firstTextValue(item, "transcript", "text", "caption", "line", "summary");
            if (!itemText.isBlank()) {
                nested.add(itemText);
            }
        }
        return String.join(" ", nested).trim();
    }

    private String firstTextValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Map<?, ?> nestedMap) {
                String nested = firstTextValue(mapValue(nestedMap), "transcript", "text", "caption", "line", "summary");
                if (!nested.isBlank()) {
                    return nested;
                }
            } else if (value instanceof List<?> list) {
                List<String> parts = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        String nested = firstTextValue(mapValue(itemMap), "transcript", "text", "caption", "line", "summary");
                        if (!nested.isBlank()) {
                            parts.add(nested);
                        }
                    } else {
                        String nested = stringValue(item, "").trim();
                        if (!nested.isBlank()) {
                            parts.add(nested);
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    return String.join(" ", parts).replaceAll("\\s+", " ").trim();
                }
            } else {
                String text = stringValue(value, "").trim();
                if (!text.isBlank()) {
                    return text.replaceAll("\\s+", " ");
                }
            }
        }
        return "";
    }

    private List<String> splitCaptionText(String value, int maxChars) {
        String text = stringValue(value, "").trim().replaceAll("\\s+", " ");
        if (text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxChars) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private void writeCaptions(CreatorShortCandidate candidate, Dimensions target, Path assFile, Map<String, Object> visualPlan, List<Map<String, Object>> captions) {
        if (visualPlan == null) {
            visualPlan = new LinkedHashMap<>();
        }
        Map<String, Object> captionStyle = mapValue(visualPlan.get("captionStyle"));
        String fontName = safeCaptionFontName(captionStyle.get("fontName"));
        captionStyle.put("fontName", fontName);
        captionStyle.put("sanitizedForAss", true);
        visualPlan.put("captionStyle", captionStyle);
        int fontSize = intValue(captionStyle.get("fontSize"), Math.max(42, Math.min(74, target.height() / 26)));
        int bottomMargin = intValue(captionStyle.get("bottomMargin"), Math.max(120, target.height() / 9));
        int leftMargin = intValue(captionStyle.get("leftMargin"), 80);
        int rightMargin = intValue(captionStyle.get("rightMargin"), 80);
        int alignment = intValue(captionStyle.get("alignment"), 2);
        double outline = doubleValue(captionStyle.get("outline"), 4.0);
        double shadow = doubleValue(captionStyle.get("shadow"), 2.0);
        int maxCharsPerLine = intValue(captionStyle.get("maxCharsPerLine"), 28);

        StringBuilder builder = new StringBuilder();
        builder.append("[Script Info]\n");
        builder.append("ScriptType: v4.00+\n");
        builder.append("PlayResX: ").append(target.width()).append('\n');
        builder.append("PlayResY: ").append(target.height()).append("\n\n");
        builder.append("[V4+ Styles]\n");
        builder.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        builder.append("Style: Default,")
                .append(fontName)
                .append(',')
                .append(fontSize)
                .append(",&H00FFFFFF,&H000000FF,&H90000000,&H80000000,1,0,0,0,100,100,0,0,1,")
                .append(formatFilterDouble(outline))
                .append(',')
                .append(formatFilterDouble(shadow))
                .append(',')
                .append(alignment)
                .append(',')
                .append(leftMargin)
                .append(',')
                .append(rightMargin)
                .append(',')
                .append(bottomMargin)
                .append(",1\n\n");
        builder.append("[Events]\n");
        builder.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        int writtenCount = 0;
        int sanitizedCount = 0;
        for (Map<String, Object> caption : captions) {
            double start = Math.max(0.0, doubleValue(caption.get("start"), 0.0));
            double end = Math.max(start + 0.5, doubleValue(caption.get("end"), start + 2.0));
            String rawText = stringValue(caption.get("text"), "");
            String sanitizedText = sanitizeCaptionText(rawText);
            if (!sanitizedText.equals(rawText.trim().replaceAll("\\s+", " "))) {
                sanitizedCount++;
            }
            String text = assEscape(wrapCaption(sanitizedText, maxCharsPerLine));
            if (text.isBlank()) {
                continue;
            }
            builder.append("Dialogue: 0,")
                    .append(assTime(start))
                    .append(',')
                    .append(assTime(end))
                    .append(",Default,,0,0,0,,")
                    .append(text)
                    .append('\n');
            writtenCount++;
        }
        try {
            Files.writeString(assFile, builder.toString(), StandardCharsets.UTF_8);
            log.info(
                    "Short render captions written candidateId={} captionCount={} sanitizedCaptionCount={} fontName={}",
                    candidate == null ? null : candidate.getId(),
                    writtenCount,
                    sanitizedCount,
                    fontName
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write short caption file.", ex);
        }
    }

    private void applyCaptions(Path input, Path assFile, Path output, Path logPath) {
        boolean hasEvents = readQuietly(assFile).contains("Dialogue:");
        if (!hasEvents) {
            run(List.of(
                    "ffmpeg",
                    "-hide_banner",
                    "-nostdin",
                    "-y",
                    "-i",
                    input.toString(),
                    "-c",
                    "copy",
                    "-movflags",
                    "+faststart",
                    output.toString()
            ), logPath, "FFmpeg short finalization failed");
            return;
        }
        run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-y",
                "-i",
                input.toString(),
                "-filter_threads",
                "1",
                "-vf",
                "subtitles=" + ffmpegFilterPath(assFile),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-threads",
                "1",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "copy",
                "-movflags",
                "+faststart",
                output.toString()
        ), logPath, "FFmpeg short caption burn-in failed");
    }

    private Map<String, Object> segmentVisualPlan(Map<String, Object> visualPlan, int index, Map<String, Object> segment, List<Map<String, Object>> retentionEffects) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (Map<String, Object> plan : listOfMaps(visualPlan.get("segmentPlans"))) {
            if (intValue(plan.get("index"), -1) == index) {
                selected.putAll(plan);
                break;
            }
            String planNodeId = stringValue(plan.get("nodeId"), "");
            String segmentNodeId = stringValue(segment.get("nodeId"), "");
            if (!planNodeId.isBlank() && planNodeId.equals(segmentNodeId)) {
                selected.putAll(plan);
                break;
            }
        }
        if (retentionEffects != null && !retentionEffects.isEmpty()) {
            selected.put("manualRetentionEffects", retentionEffects);
            retentionEffects.stream()
                    .filter(effect -> isZoomRetentionEffect(stringValue(effect.get("type"), "")))
                    .findFirst()
                    .ifPresent(effect -> {
                        selected.put("manualRetentionEffectType", stringValue(effect.get("type"), "PUNCH_ZOOM"));
                        selected.put("retentionZoomFactor", zoomFactorFor(effect));
                        selected.put("allowCrop", true);
                        selected.put("layoutMode", "crop");
                    });
        }
        Map<String, Object> speakerFocus = mapValue(segment.get("speakerFocus"));
        if (!speakerFocus.isEmpty()) {
            String anchor = stringValue(firstNonEmpty(
                    selected.get("cropAnchor"),
                    selected.get("speakerFocusCropAnchor"),
                    speakerFocus.get("cropAnchor"),
                    segment.get("speakerFocusCropAnchor")
            ), "center");
            selected.put("speaker", stringValue(firstNonEmpty(segment.get("speaker"), speakerFocus.get("speaker")), ""));
            selected.put("speakerFocus", speakerFocus);
            selected.put("speakerFocusCropAnchor", anchor);
            selected.put("cropAnchor", anchor);
            selected.put("cropMode", "speaker_focus_crop");
            selected.put("allowCrop", true);
            if (stringValue(selected.get("layoutMode"), "").isBlank()
                    || stringValue(selected.get("layoutMode"), "").contains("fit")
                    || stringValue(selected.get("layoutMode"), "").contains("pad")) {
                selected.put("layoutMode", "speaker_focus_crop");
            }
        }
        return selected;
    }

    private List<Map<String, Object>> retentionEffectsForWindow(List<Map<String, Object>> retentionPlan, double start, double end) {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (Map<String, Object> effect : retentionPlan == null ? List.<Map<String, Object>>of() : retentionPlan) {
            double effectStart = doubleValue(firstNonEmpty(effect.get("start"), effect.get("timelineStart")), 0.0);
            double effectEnd = Math.max(effectStart + 0.1, doubleValue(firstNonEmpty(effect.get("end"), effect.get("timelineEnd")), effectStart + 2.0));
            if (effectStart < end && effectEnd > start) {
                effects.add(new LinkedHashMap<>(effect));
            }
        }
        return effects;
    }

    private List<Map<String, Object>> withRetentionOverlayCaptions(List<Map<String, Object>> captions, List<Map<String, Object>> retentionPlan) {
        List<Map<String, Object>> merged = new ArrayList<>(captions == null ? List.of() : captions);
        int index = 0;
        for (Map<String, Object> effect : retentionPlan == null ? List.<Map<String, Object>>of() : retentionPlan) {
            String type = stringValue(effect.get("type"), "").toUpperCase(Locale.ROOT);
            String text = stringValue(firstNonEmpty(effect.get("text"), effect.get("label"), effect.get("overlayText")), "").trim();
            if (text.isBlank() || (!type.contains("TEXT") && !type.contains("HIGHLIGHT") && !type.contains("MEME") && !type.contains("SCREENSHOT"))) {
                continue;
            }
            double start = Math.max(0.0, doubleValue(firstNonEmpty(effect.get("start"), effect.get("timelineStart")), 0.0));
            double end = Math.max(start + 0.5, doubleValue(firstNonEmpty(effect.get("end"), effect.get("timelineEnd")), start + 1.4));
            Map<String, Object> overlay = new LinkedHashMap<>();
            overlay.put("id", "manual-retention-overlay-%03d".formatted(++index));
            overlay.put("start", round3(start));
            overlay.put("end", round3(end));
            overlay.put("text", text);
            overlay.put("source", "manual_retention_text_overlay");
            overlay.put("style", "retention_pop");
            merged.add(overlay);
        }
        return merged;
    }

    private boolean isZoomRetentionEffect(String type) {
        String normalized = stringValue(type, "").toUpperCase(Locale.ROOT);
        return normalized.contains("ZOOM") || normalized.contains("PUNCH") || normalized.contains("SHAKE");
    }

    private double zoomFactorFor(Map<String, Object> effect) {
        String type = stringValue(effect.get("type"), "").toUpperCase(Locale.ROOT);
        double intensity = clamp(doubleValue(effect.get("intensity"), 0.65), 0.0, 1.0);
        if (type.contains("ZOOM_OUT")) {
            return 1.03 + (0.04 * intensity);
        }
        if (type.contains("SHAKE")) {
            return 1.08 + (0.04 * intensity);
        }
        return 1.08 + (0.10 * intensity);
    }

    private String videoFilter(Dimensions target, Map<String, Object> visualPlan, Map<String, Object> segmentVisualPlan) {
        Map<String, Object> enhancements = mapValue(visualPlan.get("enhancements"));
        List<String> filters = new ArrayList<>();
        filters.add(layoutFilter(target, visualPlan, segmentVisualPlan));

        if (booleanValue(enhancements.get("denoise"), false)) {
            double lumaSpatial = clamp(doubleValue(enhancements.get("denoiseLumaSpatial"), 0.8), 0.0, 3.0);
            double chromaSpatial = clamp(doubleValue(enhancements.get("denoiseChromaSpatial"), 0.6), 0.0, 3.0);
            double lumaTemporal = clamp(doubleValue(enhancements.get("denoiseLumaTemporal"), 2.8), 0.0, 8.0);
            double chromaTemporal = clamp(doubleValue(enhancements.get("denoiseChromaTemporal"), 2.1), 0.0, 8.0);
            filters.add("hqdn3d=%s:%s:%s:%s".formatted(
                    formatFilterDouble(lumaSpatial),
                    formatFilterDouble(chromaSpatial),
                    formatFilterDouble(lumaTemporal),
                    formatFilterDouble(chromaTemporal)
            ));
        }

        double contrast = clamp(doubleValue(enhancements.get("contrast"), 1.0), 0.8, 1.35);
        double saturation = clamp(doubleValue(enhancements.get("saturation"), 1.0), 0.7, 1.45);
        double brightness = clamp(doubleValue(enhancements.get("brightness"), 0.0), -0.12, 0.12);
        double gamma = clamp(doubleValue(enhancements.get("gamma"), 1.0), 0.75, 1.35);
        if (Math.abs(contrast - 1.0) > 0.001 || Math.abs(saturation - 1.0) > 0.001 || Math.abs(brightness) > 0.001 || Math.abs(gamma - 1.0) > 0.001) {
            filters.add("eq=contrast=%s:saturation=%s:brightness=%s:gamma=%s".formatted(
                    formatFilterDouble(contrast),
                    formatFilterDouble(saturation),
                    formatFilterDouble(brightness),
                    formatFilterDouble(gamma)
            ));
        }

        if (booleanValue(enhancements.get("sharpen"), false)) {
            double amount = clamp(doubleValue(enhancements.get("sharpenAmount"), 0.4), 0.0, 0.9);
            if (amount > 0.0) {
                filters.add("unsharp=5:5:%s:3:3:%s".formatted(formatFilterDouble(amount), formatFilterDouble(Math.max(0.0, amount / 2.0))));
            }
        }

        if (booleanValue(enhancements.get("deband"), false)) {
            filters.add("deband");
        }
        return String.join(",", filters);
    }

    private String layoutFilter(Dimensions target, Map<String, Object> visualPlan, Map<String, Object> segmentVisualPlan) {
        double retentionZoomFactor = clamp(doubleValue(segmentVisualPlan.get("retentionZoomFactor"), 1.0), 1.0, 1.35);
        String cropAnchor = normalizedCropAnchor(firstNonEmpty(
                segmentVisualPlan.get("cropAnchor"),
                segmentVisualPlan.get("speakerFocusCropAnchor"),
                mapValue(segmentVisualPlan.get("speakerFocus")).get("cropAnchor"),
                visualPlan.get("cropAnchor")
        ));
        if (retentionZoomFactor > 1.001) {
            int zoomWidth = Math.max(target.width() + 2, (int) Math.round(target.width() * retentionZoomFactor));
            int zoomHeight = Math.max(target.height() + 2, (int) Math.round(target.height() * retentionZoomFactor));
            return "scale=w=%d:h=%d:force_original_aspect_ratio=increase:flags=lanczos,crop=%d:%d:%s:max(0\\,(in_h-out_h)/2),setsar=1"
                    .formatted(zoomWidth, zoomHeight, target.width(), target.height(), cropXExpression(cropAnchor));
        }
        String layoutMode = stringValue(firstNonEmpty(segmentVisualPlan.get("layoutMode"), visualPlan.get("layoutMode")), "fit_pad")
                .toLowerCase(Locale.ROOT);
        boolean allowCrop = booleanValue(firstNonEmpty(segmentVisualPlan.get("allowCrop"), visualPlan.get("allowCrop")), false);
        if (!allowCrop || layoutMode.contains("fit") || layoutMode.contains("pad")) {
            return "scale=w=%d:h=%d:force_original_aspect_ratio=decrease:flags=lanczos,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
                    .formatted(target.width(), target.height(), target.width(), target.height());
        }
        return "scale=w=%d:h=%d:force_original_aspect_ratio=increase:flags=lanczos,crop=%d:%d:%s:max(0\\,(in_h-out_h)/2),setsar=1"
                .formatted(target.width(), target.height(), target.width(), target.height(), cropXExpression(cropAnchor));
    }

    private String normalizedCropAnchor(Object value) {
        String anchor = stringValue(value, "center").trim().toLowerCase(Locale.ROOT);
        if (anchor.contains("left") || anchor.equals("speaker_1") || anchor.equals("speaker1")) {
            return "left";
        }
        if (anchor.contains("right") || anchor.equals("speaker_2") || anchor.equals("speaker2")) {
            return "right";
        }
        return "center";
    }

    private String cropXExpression(String anchor) {
        return switch (normalizedCropAnchor(anchor)) {
            case "left" -> "max(0\\,(in_w-out_w)*0.08)";
            case "right" -> "max(0\\,(in_w-out_w)*0.92)";
            default -> "max(0\\,(in_w-out_w)/2)";
        };
    }
    private boolean hasAudio(Path input) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "a:0",
                    "-show_entries",
                    "stream=codec_type",
                    "-of",
                    "csv=p=0",
                    input.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return finished && process.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("audio");
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not probe audio stream for source={}", input, ex);
            return true;
        }
    }

    private void run(List<String> command, Path logPath, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                    .start();
            boolean finished = process.waitFor(ffmpegTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": " + tail(readQuietly(logPath), 3000));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for Generate Shorts rendering.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Generate Shorts rendering was interrupted.", ex);
        }
    }

    private boolean renderBlockedByGlobalCritic(CreatorShortCandidate candidate) {
        Map<String, Object> renderManifest = mapValue(candidate == null ? null : candidate.getRenderManifest());
        if (Boolean.TRUE.equals(renderManifest.get("renderBlocked"))) {
            return true;
        }
        String renderStatus = stringValue(renderManifest.get("renderStatus"), "");
        String globalStatus = stringValue(renderManifest.get("globalCriticStatus"), "");
        return "BLOCKED_BY_GLOBAL_CRITIC".equalsIgnoreCase(renderStatus) || "FAIL".equalsIgnoreCase(globalStatus);
    }

    private Map<String, Object> globalCriticBlock(CreatorShortCandidate candidate) {
        Map<String, Object> renderManifest = mapValue(candidate == null ? null : candidate.getRenderManifest());
        Map<String, Object> globalCritic = mapValue(renderManifest.get("globalCritic"));
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("candidateId", candidate == null || candidate.getId() == null ? null : candidate.getId().toString());
        failure.put("rankIndex", candidate == null ? null : candidate.getRankIndex());
        failure.put("message", stringValue(globalCritic.get("summary"), "Blocked by GLOBAL_CRITIC before rendering."));
        failure.put("globalCriticStatus", stringValue(renderManifest.get("globalCriticStatus"), "FAIL"));
        failure.put("globalReadiness", stringValue(renderManifest.get("globalReadiness"), "BLOCKED_FOR_REVIEW"));
        failure.put("globalCritic", globalCritic);
        return failure;
    }

    private void markRenderBlocked(CreatorShortCandidate candidate, Map<String, Object> failure) {
        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        renderManifest.put("renderStatus", "BLOCKED_BY_GLOBAL_CRITIC");
        renderManifest.put("renderFailure", failure);
        renderManifest.put("renderedAt", OffsetDateTime.now().toString());
        Map<String, Object> metadata = mapValue(candidate.getMetadata());
        metadata.put("renderingStatus", "BLOCKED_BY_GLOBAL_CRITIC");
        metadata.put("renderFailure", failure);
        candidate.setStatus("RENDER_BLOCKED");
        candidate.setRenderManifest(renderManifest);
        candidate.setMetadata(metadata);
        candidateRepository.save(candidate);
    }
    private void markRenderFailed(CreatorShortCandidate candidate, Map<String, Object> failure) {
        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        renderManifest.put("renderStatus", "FAILED");
        renderManifest.put("renderFailure", failure);
        renderManifest.put("renderedAt", OffsetDateTime.now().toString());
        Map<String, Object> metadata = mapValue(candidate.getMetadata());
        metadata.put("renderingStatus", "FAILED");
        metadata.put("renderFailure", failure);
        candidate.setStatus("RENDER_FAILED");
        candidate.setRenderManifest(renderManifest);
        candidate.setMetadata(metadata);
        candidateRepository.save(candidate);
    }

    private void addTrace(List<Map<String, Object>> trace, String stage, String status, String summary, double confidence, Map<String, Object> details) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", confidence);
        row.put("timestamp", OffsetDateTime.now().toString());
        if (details != null && !details.isEmpty()) {
            row.put("details", details);
        }
        trace.add(row);
    }

    private String renderSummary(String status, int renderedCount, int failedCount, int deferredCount) {
        if (deferredCount > 0) {
            return "Rendered " + renderedCount + " candidates; " + failedCount + " failed; deferred " + deferredCount + " lower-ranked candidates by render cap.";
        }
        if ("FAILED".equals(status)) {
            return "Rendering failed for all candidates.";
        }
        if ("COMPLETED_WITH_WARNINGS".equals(status)) {
            return "Rendered " + renderedCount + " candidates; " + failedCount + " candidates need render repair.";
        }
        return "Rendered " + renderedCount + " short candidates and uploaded MP4 assets to MinIO.";
    }

    private String concatFile(List<Path> paths) {
        return paths.stream()
                .map(path -> "file '" + path.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''") + "'")
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private Dimensions targetDimensions(String aspectRatio) {
        String normalized = defaultString(aspectRatio, "9:16").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "16:9", "horizontal", "landscape" -> new Dimensions(1920, 1080);
            case "4:5" -> new Dimensions(1080, 1350);
            case "1:1", "square" -> new Dimensions(1080, 1080);
            default -> new Dimensions(1080, 1920);
        };
    }

    private String aspectRatioFor(CreatorShortVideo video) {
        String platform = video == null ? "" : stringValue(video.getPlatform(), "").toLowerCase(Locale.ROOT);
        if (platform.contains("instagram")) {
            return "9:16";
        }
        if (platform.contains("tiktok") || platform.contains("short")) {
            return "9:16";
        }
        return "9:16";
    }

    private String extensionFor(String contentType, String objectKey, String fallback) {
        String key = stringValue(objectKey, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        String normalized = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("matroska")) return ".mkv";
        return fallback;
    }

    private String ffmpegFilterPath(Path path) {
        return path.toAbsolutePath().toString()
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    private String assEscape(String value) {
        return stringValue(value, "")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r\n", "\\N")
                .replace("\n", "\\N");
    }

    private String wrapCaption(String value, int lineLength) {
        String text = stringValue(value, "").trim().replaceAll("\\s+", " ");
        if (text.length() <= lineLength) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        int lineChars = 0;
        for (String word : text.split(" ")) {
            if (lineChars > 0 && lineChars + word.length() + 1 > lineLength) {
                builder.append("\\N");
                lineChars = 0;
            } else if (lineChars > 0) {
                builder.append(' ');
                lineChars++;
            }
            builder.append(word);
            lineChars += word.length();
        }
        return builder.toString();
    }

    private String sanitizeCaptionText(String value) {
        String input = stringValue(value, "");
        if (input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            appendCaptionCodePoint(builder, codePoint);
        }
        String sanitized = builder.toString().replaceAll("\\s+", " ").trim();
        return sanitized.isBlank() ? "caption" : sanitized;
    }

    private void appendCaptionCodePoint(StringBuilder builder, int codePoint) {
        switch (codePoint) {
            case 0x2018, 0x2019, 0x201A, 0x201B -> {
                builder.append('\'');
                return;
            }
            case 0x201C, 0x201D, 0x201E, 0x201F -> {
                builder.append('"');
                return;
            }
            case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2212 -> {
                builder.append('-');
                return;
            }
            case 0x2026 -> {
                builder.append("...");
                return;
            }
            case '\r', '\n', '\t', 0x00A0 -> {
                builder.append(' ');
                return;
            }
            default -> {
            }
        }
        if (codePoint == 0xFFFD
                || codePoint == 0xFE0E
                || codePoint == 0xFE0F
                || codePoint == 0x200B
                || codePoint == 0x200C
                || codePoint == 0x200D
                || codePoint > Character.MAX_VALUE) {
            return;
        }
        int type = Character.getType(codePoint);
        if (Character.isISOControl(codePoint)
                || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE
                || type == Character.SURROGATE
                || type == Character.FORMAT) {
            return;
        }
        builder.appendCodePoint(codePoint);
    }

    private String assTime(double seconds) {
        int totalCentiseconds = (int) Math.round(Math.max(0.0, seconds) * 100.0);
        int centiseconds = totalCentiseconds % 100;
        int totalSeconds = totalCentiseconds / 100;
        int secs = totalSeconds % 60;
        int totalMinutes = totalSeconds / 60;
        int mins = totalMinutes % 60;
        int hours = totalMinutes / 60;
        return "%d:%02d:%02d.%02d".formatted(hours, mins, secs, centiseconds);
    }

    private String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0.0, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "yes", "1", "on").contains(normalized)) {
                return true;
            }
            if (List.of("false", "no", "0", "off").contains(normalized)) {
                return false;
            }
        }
        return fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatFilterDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String assStyleValue(Object value, String fallback) {
        return stringValue(value, fallback)
                .replace(",", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }

    private String safeCaptionFontName(Object value) {
        String safe = assStyleValue(value, DEFAULT_CAPTION_FONT);
        String normalized = safe.toLowerCase(Locale.ROOT);
        if (safe.isBlank()
                || "arial".equals(normalized)
                || "helvetica".equals(normalized)
                || "sans-serif".equals(normalized)
                || "system".equals(normalized)
                || "default".equals(normalized)
                || normalized.contains("emoji")) {
            return DEFAULT_CAPTION_FONT;
        }
        if (normalized.startsWith("noto ")
                || normalized.startsWith("dejavu ")
                || normalized.startsWith("liberation ")) {
            return safe;
        }
        return DEFAULT_CAPTION_FONT;
    }
    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String readQuietly(Path path) {
        try {
            return path == null || !Files.exists(path) ? "" : Files.readString(path);
        } catch (IOException ignored) {
            return "";
        }
    }

    private String tail(String value, int maxLength) {
        String text = stringValue(value, "");
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(text.length() - maxLength);
    }

    private String safePath(String value) {
        String normalized = defaultString(value, "unknown").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record RenderedShortsResult(
            List<Map<String, Object>> renderedCandidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }

    private record RenderedCandidate(Map<String, Object> summary) {
    }

    private record Dimensions(int width, int height) {
    }
}
