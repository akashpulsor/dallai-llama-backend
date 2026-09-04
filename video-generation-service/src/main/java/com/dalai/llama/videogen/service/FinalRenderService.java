package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.FinalRenderJob;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.FinalRenderJobRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregate assembly: concatenates every completed shot in a project into one deliverable
 * mp4 the client watches, saved back to MinIO under the project. Dispatch is a local ffmpeg
 * subprocess (not fal.ai) -- see the plan for rationale; short version: concat is CPU-only,
 * data is already in our MinIO, no reason to round-trip through a third party.
 *
 * <p>Deliberately NOT @Transactional -- ffmpeg blocks for seconds to minutes; wrapping the
 * whole flow would hold a pooled DB connection for that entire window. Each status
 * transition commits on its own via {@link FinalRenderJobPersistenceService}, same reasoning
 * {@link ShotGenerationOrchestrator#approve} already documents.
 *
 * <p>Ffmpeg command shape mirrors creator-service's proven {@code FinalVideoRenderer}:
 * {@code ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -movflags +faststart output.mp4}.
 * If -c copy fails (mismatched streams -- rare when every shot uses the same model but
 * possible), fall back to a re-encode via filter_complex. Both variants are still pure CPU.
 */
@Slf4j
@Service
public class FinalRenderService {

    private final VideoGenJobRepository videoGenJobRepository;
    private final FinalRenderJobRepository finalRenderJobRepository;
    private final FinalRenderJobPersistenceService jobPersistenceService;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final PreProductionServiceClient preProductionServiceClient;
    private final String bucket;
    private final String prefix;

    public FinalRenderService(
            VideoGenJobRepository videoGenJobRepository,
            FinalRenderJobRepository finalRenderJobRepository,
            FinalRenderJobPersistenceService jobPersistenceService,
            VideoAssetPersistenceService assetPersistenceService,
            PreProductionServiceClient preProductionServiceClient,
            @Value("${video-gen.minio.bucket}") String bucket,
            @Value("${video-gen.minio.export-prefix}") String exportPrefix
    ) {
        this.videoGenJobRepository = videoGenJobRepository;
        this.finalRenderJobRepository = finalRenderJobRepository;
        this.jobPersistenceService = jobPersistenceService;
        this.assetPersistenceService = assetPersistenceService;
        this.preProductionServiceClient = preProductionServiceClient;
        this.bucket = bucket;
        // Sibling prefix to per-shot clips, distinct so lifecycle policies can differ later.
        this.prefix = exportPrefix.replaceAll("-exports$", "") + "-finals";
    }

    public FinalRenderJob assemble(TenantContext tenantContext, UUID projectId) {
        UUID tenantId = tenantContext.tenantId();

        // 1. Enumerate shots pre-prod knows about (canonical order + expected count).
        List<PreProductionViews.ShotView> preProdShots = preProductionServiceClient.listShots(tenantId, projectId);
        if (preProdShots.isEmpty()) {
            throw VideoGenException.badRequest("Project " + projectId + " has no shots to assemble");
        }

        // 2. Enumerate completed video-gen jobs, dedupe by shotRef keeping newest (same rule
        //    listJobsForProject already uses). Filter to COMPLETED with a durable MinIO output.
        List<VideoGenJob> jobs = videoGenJobRepository.findByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId);
        Map<String, VideoGenJob> latestByShotRef = new LinkedHashMap<>();
        for (VideoGenJob job : jobs) {
            if (job.getStatus() == JobStatus.COMPLETED
                    && job.getOutputBucket() != null && job.getOutputObjectKey() != null
                    && !latestByShotRef.containsKey(job.getShotRef())) {
                latestByShotRef.put(job.getShotRef(), job);
            }
        }

        // 3. Check completeness: every pre-prod shot must have a completed video job. Refuse
        //    (409) rather than silently producing a truncated deliverable.
        List<PreProductionViews.ShotView> missing = preProdShots.stream()
                .filter(s -> s.shotRef() == null || !latestByShotRef.containsKey(s.shotRef()))
                .toList();
        if (!missing.isEmpty()) {
            String missingRefs = missing.stream()
                    .map(s -> s.shotRef() == null ? "(unnamed)" : s.shotRef())
                    .collect(Collectors.joining(", "));
            throw VideoGenException.conflict("Cannot assemble -- these shots have no completed video yet: " + missingRefs);
        }

        // 4. Order jobs by pre-prod's shotNumber (canonical narrative order).
        List<VideoGenJob> ordered = preProdShots.stream()
                .sorted(Comparator.comparing(s -> Optional.ofNullable(s.shotNumber()).orElse(Integer.MAX_VALUE)))
                .map(s -> latestByShotRef.get(s.shotRef()))
                .toList();

        // 5. Create the FinalRenderJob row (PENDING_APPROVAL). Persist BEFORE the long work
        //    starts so a poll or a crash can still find/reconcile it.
        UUID renderId = UUID.randomUUID();
        FinalRenderJob renderJob = jobPersistenceService.create(FinalRenderJob.builder()
                .renderId(renderId)
                .tenantId(tenantId)
                .projectId(projectId)
                .createdBy(tenantContext.userId())
                .status(JobStatus.PENDING_APPROVAL)
                .shotCount(ordered.size())
                .createdAt(OffsetDateTime.now())
                .build());
        jobPersistenceService.markProcessing(renderId);

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("final-render-" + renderId + "-");
            // 6. Download each shot's MinIO object to a local temp file.
            List<Path> clipPaths = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                VideoGenJob job = ordered.get(i);
                Path clipPath = workDir.resolve("%03d-%s.mp4".formatted(i + 1, job.getJobId()));
                assetPersistenceService.downloadTo(job.getOutputBucket(), job.getOutputObjectKey(), clipPath);
                clipPaths.add(clipPath);
            }

            // 7. Concat via ffmpeg. Try fast -c copy path first, fall back to re-encode
            //    if the streams don't match up.
            Path output = workDir.resolve("final.mp4");
            runConcatWithCopy(workDir, clipPaths, output);
            if (!Files.exists(output) || Files.size(output) == 0) {
                log.info("Fast -c copy concat produced no output, falling back to re-encode for renderId={}", renderId);
                runConcatWithReencode(clipPaths, output);
            }
            if (!Files.exists(output) || Files.size(output) == 0) {
                throw VideoGenException.upstream("ffmpeg produced no output for renderId=" + renderId);
            }

            // 8. Upload the assembled mp4 back to MinIO.
            String objectKey = "%s/%s.mp4".formatted(prefix, renderId);
            assetPersistenceService.uploadFile(bucket, objectKey, output);

            // 9. Terminal state.
            String sourceJobIds = ordered.stream()
                    .map(j -> j.getJobId().toString())
                    .collect(Collectors.joining(","));
            return jobPersistenceService.finishSuccess(renderId, bucket, objectKey, ordered.size(), sourceJobIds);
        } catch (RuntimeException ex) {
            log.warn("Final render failed renderId={} errorMessage={}", renderId, ex.getMessage());
            jobPersistenceService.finishFailure(renderId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("Final render failed renderId={} errorMessage={}", renderId, ex.getMessage());
            jobPersistenceService.finishFailure(renderId, ex.getMessage());
            throw VideoGenException.upstream("Final render failed: " + ex.getMessage());
        } finally {
            deleteQuietly(workDir);
        }
    }

    public Optional<FinalRenderJob> getLatest(UUID tenantId, UUID projectId) {
        return finalRenderJobRepository.findTopByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId);
    }

    public FinalRenderJob require(UUID tenantId, UUID renderId) {
        return finalRenderJobRepository.findById(renderId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown render_id=" + renderId));
    }

    public String getVideoUrl(UUID tenantId, UUID renderId) {
        FinalRenderJob job = require(tenantId, renderId);
        if (job.getOutputBucket() == null || job.getOutputObjectKey() == null) {
            throw VideoGenException.notFound("render_id=%s has no persisted output yet (status=%s)"
                    .formatted(renderId, job.getStatus()));
        }
        return assetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
    }

    /** Fast path: {@code -c copy} concat. Requires every input to share codec/framerate/
     * resolution -- true when every shot came from the same model at the same settings
     * (typical for a project), fails cleanly otherwise. */
    private void runConcatWithCopy(Path workDir, List<Path> clipPaths, Path output) {
        try {
            Path concatFile = workDir.resolve("concat.txt");
            Files.writeString(concatFile, buildConcatManifest(clipPaths));
            runFfmpeg(List.of(
                    "ffmpeg", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile.toString(),
                    "-c", "copy",
                    "-movflags", "+faststart",
                    output.toString()
            ));
        } catch (IOException | RuntimeException ex) {
            log.info("Fast -c copy concat failed, will try re-encode: {}", ex.getMessage());
            try { Files.deleteIfExists(output); } catch (IOException ignored) {}
        }
    }

    /** Re-encode fallback via filter_complex concat -- handles mismatched codecs/framerates/
     * resolutions at the cost of CPU work. Still no GPU needed. */
    private void runConcatWithReencode(List<Path> clipPaths, Path output) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        for (Path clip : clipPaths) {
            command.add("-i");
            command.add(clip.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < clipPaths.size(); i++) {
            filter.append("[").append(i).append(":v:0][").append(i).append(":a:0?]");
        }
        filter.append("concat=n=").append(clipPaths.size()).append(":v=1:a=1[v][a]");
        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[v]");
        command.add("-map");
        command.add("[a]");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        runFfmpeg(command);
    }

    private void runFfmpeg(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Drain output so the process's stderr pipe never fills up and blocks it.
            StringBuilder tail = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.length() < 4096) {
                        tail.append(line).append('\n');
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new RuntimeException("ffmpeg exit=" + exit + " tail=" + tail);
            }
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("ffmpeg invocation failed: " + ex.getMessage(), ex);
        }
    }

    private String buildConcatManifest(List<Path> clipPaths) {
        StringBuilder sb = new StringBuilder();
        for (Path clip : clipPaths) {
            // -safe 0 lets us use absolute paths; single-quote and escape any embedded quotes.
            sb.append("file '").append(clip.toString().replace("'", "'\\''")).append("'\n");
        }
        return sb.toString();
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }
}
