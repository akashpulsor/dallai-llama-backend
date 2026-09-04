package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ShotExportJob;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.dto.ExportView;
import com.dalai.llama.preprod.repository.ShotExportJobRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.domain.entity.Shot;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * One page per shot: shot metadata (number, type, action, camera) plus every generated {@link
 * ShotImageKind} laid out in a 2x2 grid -- what a creator hands a client instead of a live login.
 *
 * <p>Memory-safe pipeline: raw provider images (typically ~4Kx4K PNGs, 5-20MB each) never sit in
 * the JVM heap. Each image is streamed download-to-temp-file, then decoded/downscaled/JPEG-
 * re-encoded to a smaller temp file (raw temp deleted immediately), and only during final PDF
 * assembly do we read one compressed file at a time (~100KB) into memory to embed. Peak heap
 * per export: ~1 raw BufferedImage's decode footprint (~50MB for a 4K PNG), never the whole
 * project's image set at once.
 */
@Service
public class ShotExportService {

    private static final float PAGE_MARGIN = 40f;
    private static final float IMAGE_CELL = 240f;
    private static final PDFont TITLE_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont BODY_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont LABEL_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    /** Images render into a 240pt display cell (~80mm at 72dpi). 600px on the long edge gives
     * ~2.5x the display size, comfortable for zoom-in / print quality without paying to embed the
     * raw ~4Kx4K provider output at full resolution. */
    private static final int MAX_IMAGE_DIMENSION_PX = 600;
    /** JPEGFactory quality 0..1. 0.82 is the standard "web quality" sweet spot -- indistinguishable
     * from source on a shot card, roughly 10-20x smaller than raw PNG source. */
    private static final float JPEG_QUALITY = 0.82f;

    private final ShotRepository shotRepository;
    private final ShotImageRepository shotImageRepository;
    private final ShotExportJobRepository shotExportJobRepository;
    private final ProjectService projectService;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final String bucket;
    private final String exportPrefix;

    public ShotExportService(
            ShotRepository shotRepository,
            ShotImageRepository shotImageRepository,
            ShotExportJobRepository shotExportJobRepository,
            ProjectService projectService,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.export-prefix}") String exportPrefix
    ) {
        this.shotRepository = shotRepository;
        this.shotImageRepository = shotImageRepository;
        this.shotExportJobRepository = shotExportJobRepository;
        this.projectService = projectService;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.bucket = bucket;
        this.exportPrefix = exportPrefix;
    }

    /** Not @Transactional(readOnly=true) anymore because the stamp-in-db write at the end needs
     * to commit; the reads are still just plain SELECTs against the shot/shot_image tables. */
    @Transactional
    public ExportView exportPdf(UUID tenantId, UUID projectId, UUID userId) {
        projectService.requireProject(tenantId, projectId);
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        if (shots.isEmpty()) {
            throw PreProductionException.badRequest("Project " + projectId + " has no shots yet");
        }

        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory("shot-pdf-export-");
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not create staging dir for shot export PDF: " + ex.getMessage());
        }

        try {
            Map<UUID, Map<ShotImageKind, Path>> stagedByShot = stageAllImages(shots, stagingDir);
            byte[] pdfBytes = buildPdf(shots, stagedByShot);
            UploadResult uploaded = upload(projectId, pdfBytes);
            ShotExportJob record = stampExport(tenantId, projectId, userId, uploaded, shots.size(), pdfBytes.length);
            return new ExportView(record.getId(), uploaded.bucket(), uploaded.objectKey(), uploaded.signedUrl());
        } catch (PreProductionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not build shot export PDF: " + ex.getMessage());
        } finally {
            deleteRecursivelyQuietly(stagingDir);
        }
    }

    /** Re-signs an existing export from its persisted MinIO location -- the row from
     * {@link ShotExportJobRepository} is the durable source, the signed URL just expires and
     * gets minted fresh here so a browser download link keeps working across sessions.
     * Tenant-scoped find prevents one tenant handing another tenant's export id and getting a
     * URL back for it. */
    @Transactional(readOnly = true)
    public ExportView getExport(UUID tenantId, UUID exportId) {
        ShotExportJob record = shotExportJobRepository.findByIdAndTenantId(exportId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot export id=" + exportId));
        String signedUrl = signGet(record.getBucket(), record.getObjectKey());
        return new ExportView(record.getId(), record.getBucket(), record.getObjectKey(), signedUrl);
    }

    @Transactional(readOnly = true)
    public List<ExportView> listExports(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        return shotExportJobRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(record -> new ExportView(record.getId(), record.getBucket(), record.getObjectKey(),
                        signGet(record.getBucket(), record.getObjectKey())))
                .collect(Collectors.toList());
    }

    private ShotExportJob stampExport(UUID tenantId, UUID projectId, UUID userId, UploadResult uploaded,
                                      int shotCount, long fileSizeBytes) {
        ShotExportJob record = ShotExportJob.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .createdBy(userId)
                .bucket(uploaded.bucket())
                .objectKey(uploaded.objectKey())
                .shotCount(shotCount)
                .fileSizeBytes(fileSizeBytes)
                .createdAt(OffsetDateTime.now())
                .build();
        return shotExportJobRepository.save(record);
    }

    /** Phase 1: download every shot image to disk, compress in place, delete the raw. Bytes only
     * touch the heap during the transient AWT decode/scale of ONE image at a time -- never all
     * raw images at once, and never as a byte[] intermediate for the raw file itself. */
    private Map<UUID, Map<ShotImageKind, Path>> stageAllImages(List<Shot> shots, Path stagingDir) throws Exception {
        Map<UUID, Map<ShotImageKind, Path>> stagedByShot = new HashMap<>();
        for (Shot shot : shots) {
            Map<ShotImageKind, ShotImage> imagesByKind = shotImageRepository.findByShotId(shot.getId()).stream()
                    .collect(Collectors.toMap(ShotImage::getKind, i -> i, (a, b) -> a));
            Map<ShotImageKind, Path> staged = new EnumMap<>(ShotImageKind.class);
            for (Map.Entry<ShotImageKind, ShotImage> entry : imagesByKind.entrySet()) {
                Path compressed = stageAndCompress(shot.getId(), entry.getKey(), entry.getValue(), stagingDir);
                if (compressed != null) {
                    staged.put(entry.getKey(), compressed);
                }
            }
            stagedByShot.put(shot.getId(), staged);
        }
        return stagedByShot;
    }

    /** Streams the raw image from MinIO to a temp file (8KB read buffer inside {@link Files#copy}
     * -- raw bytes never sit fully in heap), then decodes/scales/JPEG-writes to a smaller temp
     * file and deletes the raw. A decode failure -- rare, would mean an unsupported provider
     * format, not a corrupt upload -- keeps the raw file as-is; PDF assembly will embed it via
     * PDFBox's own byte-array path so one broken image never fails the whole export. */
    private Path stageAndCompress(UUID shotId, ShotImageKind kind, ShotImage image, Path stagingDir) throws Exception {
        Path raw = stagingDir.resolve(shotId + "-" + kind + "-raw");
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(image.getBucket()).object(image.getObjectKey()).build())) {
            Files.copy(in, raw, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not download shot image from MinIO shotId=" + shotId + " kind=" + kind + ": " + ex.getMessage());
        }

        Path compressed = stagingDir.resolve(shotId + "-" + kind + ".jpg");
        BufferedImage source = ImageIO.read(raw.toFile());
        if (source == null) {
            // Unsupported format -- keep the raw file, PDF assembly will fall back to the raw
            // bytes for this one cell. Never happens for the PNG/JPEG output every current
            // provider returns; guards against a future provider format we don't yet decode.
            return raw;
        }
        BufferedImage scaled = downscale(source);
        try {
            ImageIO.write(scaled, "jpg", compressed.toFile());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not compress shot image shotId=" + shotId + " kind=" + kind + ": " + ex.getMessage());
        }
        Files.deleteIfExists(raw);
        return compressed;
    }

    /** Phase 2: assemble PDF from the on-disk compressed images. Each page reads its images
     * lazily -- one small compressed byte[] at a time -- and PDFBox holds them internally in the
     * document until save(). Full PDF materializes into one ByteArrayOutputStream at the end,
     * bounded by (image count x compressed image size) which is ~5-8MB for a typical project
     * post-compression -- well under any reasonable heap concern. */
    private byte[] buildPdf(List<Shot> shots, Map<UUID, Map<ShotImageKind, Path>> stagedByShot) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (Shot shot : shots) {
                renderShotPage(document, shot, stagedByShot.getOrDefault(shot.getId(), Map.of()));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void renderShotPage(PDDocument document, Shot shot, Map<ShotImageKind, Path> stagedImages) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

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

            float rowStartY = y;
            int col = 0;
            for (ShotImageKind kind : ShotImageKind.values()) {
                Path staged = stagedImages.get(kind);
                float cellX = PAGE_MARGIN + col * (IMAGE_CELL + 15);
                float cellY = rowStartY - IMAGE_CELL;
                if (staged != null) {
                    PDImageXObject pdImage = embedStagedImage(document, staged, kind);
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

    /** Reads ONE compressed image from disk and hands it to PDFBox. Since compression already
     * happened in the staging pass, the byte[] here is ~100KB not ~5MB -- fine to hold in heap
     * for the duration of a single drawImage call. */
    private PDImageXObject embedStagedImage(PDDocument document, Path stagedImage, ShotImageKind kind) throws Exception {
        byte[] bytes = Files.readAllBytes(stagedImage);
        if (stagedImage.getFileName().toString().endsWith(".jpg")) {
            return JPEGFactory.createFromStream(document, new ByteArrayInputStream(bytes));
        }
        // Fallback path: staging decode failed and kept the raw file. Let PDFBox sniff format.
        return PDImageXObject.createFromByteArray(document, bytes, kind.toString());
    }

    /** Aspect-preserving downscale to a max long-edge of {@code MAX_IMAGE_DIMENSION_PX}. Returns
     * the source unchanged if it's already at or below that size. */
    private BufferedImage downscale(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= MAX_IMAGE_DIMENSION_PX) {
            return source;
        }
        double scale = (double) MAX_IMAGE_DIMENSION_PX / longEdge;
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source.getScaledInstance(newW, newH, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        return scaled;
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

    /** MinIO write side + fresh signed URL. Doesn't stamp the receipt row -- that's the caller's
     * job so a failure between upload and stamp doesn't leave the DB pointing at an object that
     * doesn't exist, and so getExport / listExports (which don't upload) can call {@link
     * #signGet} directly on their own. */
    private UploadResult upload(UUID projectId, byte[] pdfBytes) {
        String objectKey = "%s/%s/%s.pdf".formatted(exportPrefix, projectId, UUID.randomUUID());
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                    .contentType("application/pdf")
                    .build());
            return new UploadResult(bucket, objectKey, signGet(bucket, objectKey));
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload shot export PDF: " + ex.getMessage());
        }
    }

    private String signGet(String bucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign shot export URL bucket=" + bucket + " objectKey=" + objectKey + ": " + ex.getMessage());
        }
    }

    private record UploadResult(String bucket, String objectKey, String signedUrl) { }

    /** Best-effort cleanup: walk the staging dir depth-first, delete each entry, swallow any
     * IO error. Nothing else depends on the temp files after export completes, and leaking
     * a few MB of stale JPEGs is strictly better than throwing over cleanup failure. */
    private void deleteRecursivelyQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            entries.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : entries) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }
}
