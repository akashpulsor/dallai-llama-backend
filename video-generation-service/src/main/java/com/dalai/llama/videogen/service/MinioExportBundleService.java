package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ExportStatus;
import com.dalai.llama.videogen.domain.entity.ExportBundle;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.ShotPromptReference;
import com.dalai.llama.videogen.repository.ExportBundleRepository;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class MinioExportBundleService implements ExportBundleService {

    private final MinioClient minioClient;
    private final ShotPromptRepository shotPromptRepository;
    private final ShotPromptReferenceRepository shotPromptReferenceRepository;
    private final ExportBundleRepository exportBundleRepository;
    private final String exportBucket;
    private final String exportPrefix;

    public MinioExportBundleService(
            MinioClient minioClient,
            ShotPromptRepository shotPromptRepository,
            ShotPromptReferenceRepository shotPromptReferenceRepository,
            ExportBundleRepository exportBundleRepository,
            @Value("${video-gen.minio.bucket}") String exportBucket,
            @Value("${video-gen.minio.export-prefix}") String exportPrefix
    ) {
        this.minioClient = minioClient;
        this.shotPromptRepository = shotPromptRepository;
        this.shotPromptReferenceRepository = shotPromptReferenceRepository;
        this.exportBundleRepository = exportBundleRepository;
        this.exportBucket = exportBucket;
        this.exportPrefix = exportPrefix;
    }

    @Override
    public ExportBundle requestExport(UUID tenantId, UUID promptId) {
        ShotPrompt prompt = shotPromptRepository.findById(promptId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown prompt_id: " + promptId));

        ExportBundle bundle = ExportBundle.builder()
                .bundleId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(prompt.getProjectId())
                .promptId(promptId)
                .status(ExportStatus.BUILDING)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();
        exportBundleRepository.save(bundle);

        try {
            String objectKey = buildAndUploadZip(bundle, prompt);
            bundle.setObjectKey(objectKey);
            bundle.setStatus(ExportStatus.READY);
        } catch (Exception ex) {
            log.warn("Export bundle build failed bundleId={} promptId={} errorMessage={}", bundle.getBundleId(), promptId, ex.getMessage());
            bundle.setStatus(ExportStatus.FAILED);
        }
        return exportBundleRepository.save(bundle);
    }

    @Override
    public ExportBundle getStatus(UUID tenantId, UUID bundleId) {
        return exportBundleRepository.findById(bundleId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown bundle_id: " + bundleId));
    }

    private String buildAndUploadZip(ExportBundle bundle, ShotPrompt prompt) throws Exception {
        List<ShotPromptReference> references = shotPromptReferenceRepository.findByPromptId(prompt.getPromptId());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            writeTextEntry(zip, "prompt.txt", firstNonNull(prompt.getPromptCompressed(), prompt.getPromptOriginal()));
            writeTextEntry(zip, "prompt_original.txt", prompt.getPromptOriginal());
            writeTextEntry(zip, "negative_prompt.txt", prompt.getNegativePrompt());
            writeTextEntry(zip, "metadata.json", metadataJson(prompt));

            for (ShotPromptReference reference : references) {
                try (InputStream refStream = minioClient.getObject(GetObjectArgs.builder()
                        .bucket(reference.getBucket())
                        .object(reference.getObjectKey())
                        .build())) {
                    zip.putNextEntry(new ZipEntry("references/" + reference.getRefKind() + "_" + reference.getSlotIndex()));
                    refStream.transferTo(zip);
                    zip.closeEntry();
                } catch (Exception ex) {
                    log.warn("Skipping unreadable reference bucket={} objectKey={} errorMessage={}",
                            reference.getBucket(), reference.getObjectKey(), ex.getMessage());
                }
            }
        }

        String objectKey = "%s/%s/%s.zip".formatted(exportPrefix, prompt.getJobId(), bundle.getBundleId());
        byte[] zipBytes = buffer.toByteArray();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(exportBucket)
                .object(objectKey)
                .stream(new ByteArrayInputStream(zipBytes), zipBytes.length, -1)
                .contentType("application/zip")
                .build());
        return objectKey;
    }

    private void writeTextEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String metadataJson(ShotPrompt prompt) {
        return """
                {"promptId":"%s","jobId":"%s","shipped":%s,"compressionApplied":%s,"createdAt":"%s"}
                """.formatted(prompt.getPromptId(), prompt.getJobId(), prompt.getShipped(),
                prompt.getCompressionApplied(), prompt.getCreatedAt());
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
