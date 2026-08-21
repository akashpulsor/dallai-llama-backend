package com.dalai.llama.creator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side replacement for the animated storyboard export's "make it offline" step, which
 * used to run in the browser (fetch each image via a tainted-canvas-prone crossOrigin canvas
 * fetch, watermark it, inline as base64). That depends on CORS and the signed URL being
 * reachable/trusted from wherever the browser sits, which is exactly what breaks in a local dev
 * environment. This service reads the same image bytes directly from object storage instead - no
 * HTTP fetch, no CORS, no signed-URL expiry race - since every asset URL this app hands out
 * already encodes its own bucket/objectKey in its path (see {@link AssetStorageService#signedUrl}).
 * Watermark parameters intentionally mirror storyboardAnimatedExport.js's drawTiledWatermark
 * exactly (alpha, rotation, tiling density) so the output looks the same as the working
 * client-side path did; the font itself is a close system fallback since Inter isn't bundled
 * server-side, not a pixel-exact match.
 */
@Service
public class StoryboardOfflineExportService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardOfflineExportService.class);
    private static final Pattern IMAGE_SRC_PATTERN = Pattern.compile("src=\"(https?://[^\"]+)\"");
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final float WATERMARK_ALPHA = 0.16f;
    private static final double WATERMARK_ROTATION_RADIANS = -Math.PI / 8;
    private static final float WATERMARK_FONT_SIZE_RATIO = 0.032f;
    private static final float WATERMARK_STEP_MULTIPLIER = 4f;

    private final AssetStorageService assetStorageService;

    public StoryboardOfflineExportService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public String embedOfflineWatermarkedImages(String html, String watermarkText) {
        if (html == null || html.isBlank()) {
            return html;
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = IMAGE_SRC_PATTERN.matcher(html);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        if (urls.isEmpty()) {
            return html;
        }

        String safeWatermarkText = watermarkText == null ? "" : watermarkText.trim();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, urls.size()));
        try {
            List<CompletableFuture<Map.Entry<String, String>>> futures = urls.stream()
                    .map(url -> CompletableFuture.supplyAsync(
                            () -> Map.entry(url, watermarkedDataUriOrOriginal(url, safeWatermarkText)),
                            pool
                    ))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            String result = html;
            for (CompletableFuture<Map.Entry<String, String>> future : futures) {
                Map.Entry<String, String> entry = future.join();
                if (entry.getValue() == null) {
                    continue;
                }
                result = result.replace("src=\"" + entry.getKey() + "\"", "src=\"" + entry.getValue() + "\"");
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private String watermarkedDataUriOrOriginal(String url, String watermarkText) {
        try {
            return watermarkedDataUri(url, watermarkText);
        } catch (RuntimeException | IOException ex) {
            log.warn("Skipping offline-embed for one storyboard image (left as a live URL) urlHost={} errorType={}",
                    safeHost(url), ex.getClass().getSimpleName());
            return null;
        }
    }

    private String watermarkedDataUri(String url, String watermarkText) throws IOException {
        Map.Entry<String, String> bucketAndKey = bucketAndObjectKey(url);
        if (bucketAndKey == null) {
            return null;
        }
        byte[] sourceBytes;
        try (AssetStorageService.StreamedObject stored = assetStorageService.openObjectStream(bucketAndKey.getKey(), bucketAndKey.getValue())) {
            if (stored.sizeBytes() > MAX_IMAGE_BYTES) {
                return null;
            }
            sourceBytes = stored.inputStream().readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (sourceBytes.length == 0 || sourceBytes.length > MAX_IMAGE_BYTES) {
                return null;
            }
        }

        BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(sourceBytes));
        if (source == null) {
            return null;
        }
        BufferedImage flattened = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D flattenGraphics = flattened.createGraphics();
        flattenGraphics.setColor(Color.WHITE);
        flattenGraphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        flattenGraphics.drawImage(source, 0, 0, null);
        flattenGraphics.dispose();

        if (!watermarkText.isBlank()) {
            drawTiledWatermark(flattened, watermarkText);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(flattened, "jpg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private void drawTiledWatermark(BufferedImage image, String text) {
        int width = image.getWidth();
        int height = image.getHeight();
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, WATERMARK_ALPHA));
            g.setColor(Color.WHITE);
            int fontSize = Math.max(14, Math.round(width * WATERMARK_FONT_SIZE_RATIO));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            int baselineOffset = (metrics.getAscent() - metrics.getDescent()) / 2;

            double diagonal = Math.ceil(Math.sqrt((double) width * width + (double) height * height));
            g.translate(width / 2.0, height / 2.0);
            g.rotate(WATERMARK_ROTATION_RADIANS);
            double stepX = textWidth + fontSize * WATERMARK_STEP_MULTIPLIER;
            double stepY = fontSize * WATERMARK_STEP_MULTIPLIER;
            for (double y = -diagonal; y < diagonal; y += stepY) {
                for (double x = -diagonal; x < diagonal; x += stepX) {
                    g.drawString(text, (float) x, (float) (y + baselineOffset));
                }
            }
        } finally {
            g.dispose();
        }
    }

    private Map.Entry<String, String> bucketAndObjectKey(String url) {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String path = parsed.getPath();
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        String withoutLeadingSlash = path.substring(1);
        int slashIndex = withoutLeadingSlash.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String bucket = withoutLeadingSlash.substring(0, slashIndex);
        if (!bucket.equals(assetStorageService.creatorAssetsBucket())) {
            return null;
        }
        String objectKey = URLDecoder.decode(withoutLeadingSlash.substring(slashIndex + 1), StandardCharsets.UTF_8);
        if (objectKey.isBlank()) {
            return null;
        }
        return Map.entry(bucket, objectKey);
    }

    private String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }
}
