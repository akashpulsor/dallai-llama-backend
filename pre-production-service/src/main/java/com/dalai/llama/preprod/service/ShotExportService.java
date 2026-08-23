package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.dto.ExportView;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.domain.entity.Shot;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * One page per shot: shot metadata (number, type, action, camera) plus every generated {@link
 * ShotImageKind} laid out in a 2x2 grid -- what a creator hands a client instead of a live login.
 * Pure Java (PDFBox), same dependency creative-planning-service's own PDF exports already use;
 * this is new layout code, not a shared library, since no PDF-building code is shared across
 * services in this codebase.
 */
@Service
public class ShotExportService {

    private static final float PAGE_MARGIN = 40f;
    private static final float IMAGE_CELL = 240f;
    private static final PDFont TITLE_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont BODY_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont LABEL_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final ShotRepository shotRepository;
    private final ShotImageRepository shotImageRepository;
    private final ProjectService projectService;
    private final MinioClient minioClient;
    private final String bucket;
    private final String exportPrefix;

    public ShotExportService(
            ShotRepository shotRepository,
            ShotImageRepository shotImageRepository,
            ProjectService projectService,
            MinioClient minioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.export-prefix}") String exportPrefix
    ) {
        this.shotRepository = shotRepository;
        this.shotImageRepository = shotImageRepository;
        this.projectService = projectService;
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.exportPrefix = exportPrefix;
    }

    @Transactional(readOnly = true)
    public ExportView exportPdf(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        if (shots.isEmpty()) {
            throw PreProductionException.badRequest("Project " + projectId + " has no shots yet");
        }

        try (PDDocument document = new PDDocument()) {
            for (Shot shot : shots) {
                renderShotPage(document, shot);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return upload(projectId, out.toByteArray());
        } catch (PreProductionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not build shot export PDF: " + ex.getMessage());
        }
    }

    private void renderShotPage(PDDocument document, Shot shot) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        Map<ShotImageKind, ShotImage> imagesByKind = shotImageRepository.findByShotId(shot.getId()).stream()
                .collect(Collectors.toMap(ShotImage::getKind, i -> i, (a, b) -> a));

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            float pageHeight = page.getMediaBox().getHeight();
            float y = pageHeight - PAGE_MARGIN;

            y = writeLine(cs, TITLE_FONT, 16, PAGE_MARGIN, y, "Shot " + shot.getShotNumber() + " (" + shot.getShotType() + ")");
            y -= 6;
            if (shot.getAction() != null) {
                y = writeWrapped(cs, BODY_FONT, 11, PAGE_MARGIN, y, shot.getAction(), page.getMediaBox().getWidth() - 2 * PAGE_MARGIN);
            }
            String camera = "Camera: " + nullSafe(shot.getCameraShotSize()) + " / " + nullSafe(shot.getCameraAngle())
                    + " / " + nullSafe(shot.getCameraMovement()) + "   Location: " + nullSafe(shot.getLocation());
            y = writeLine(cs, BODY_FONT, 9, PAGE_MARGIN, y - 8, camera);
            y -= 18;

            float x = PAGE_MARGIN;
            float rowStartY = y;
            int col = 0;
            for (ShotImageKind kind : ShotImageKind.values()) {
                ShotImage image = imagesByKind.get(kind);
                float cellX = PAGE_MARGIN + col * (IMAGE_CELL + 15);
                float cellY = rowStartY - IMAGE_CELL;
                if (image != null) {
                    byte[] bytes = downloadBytes(image.getBucket(), image.getObjectKey());
                    PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, bytes, image.getObjectKey());
                    cs.drawImage(pdImage, cellX, cellY, IMAGE_CELL, IMAGE_CELL);
                } else {
                    cs.setNonStrokingColor(0.9f, 0.9f, 0.9f);
                    cs.addRect(cellX, cellY, IMAGE_CELL, IMAGE_CELL);
                    cs.fill();
                    cs.setNonStrokingColor(0f, 0f, 0f);
                }
                writeLine(cs, LABEL_FONT, 8, cellX, cellY - 12, kind.toString());
                col++;
                if (col == 2) {
                    col = 0;
                    rowStartY -= (IMAGE_CELL + 30);
                }
            }
        }
    }

    private float writeLine(PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
        return y - size - 4;
    }

    /** Naive word-wrap by character-width estimate -- good enough for a shot's action line, not a
     * general-purpose text layout engine. */
    private float writeWrapped(PDPageContentStream cs, PDFont font, float size, float x, float y, String text, float maxWidth) throws Exception {
        String[] words = sanitize(text).split("\\s+");
        StringBuilder line = new StringBuilder();
        float lineY = y;
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            float width = font.getStringWidth(candidate) / 1000 * size;
            if (width > maxWidth && !line.isEmpty()) {
                lineY = writeLine(cs, font, size, x, lineY, line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lineY = writeLine(cs, font, size, x, lineY, line.toString());
        }
        return lineY;
    }

    /** PDFBox's base-14 fonts only encode WinAnsi -- strip anything outside it rather than let a
     * stray character throw mid-render and lose the whole export. */
    private String sanitize(String text) {
        return text == null ? "" : text.replaceAll("[^\\x00-\\xFF]", "?");
    }

    private String nullSafe(Object value) {
        return value == null ? "-" : value.toString();
    }

    private byte[] downloadBytes(String sourceBucket, String objectKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder().bucket(sourceBucket).object(objectKey).build())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not read shot image from MinIO: " + ex.getMessage());
        }
    }

    private ExportView upload(UUID projectId, byte[] pdfBytes) {
        String objectKey = "%s/%s/%s.pdf".formatted(exportPrefix, projectId, UUID.randomUUID());
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new java.io.ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                    .contentType("application/pdf")
                    .build());
            String signedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
            return new ExportView(bucket, objectKey, signedUrl);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload shot export PDF: " + ex.getMessage());
        }
    }
}
