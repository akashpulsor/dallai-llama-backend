package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A single self-contained HTML page that flips through each shot's best available image with a
 * Ken-Burns pan/zoom, autoplay, and prev/next controls -- a live, static-image storyboard preview
 * a creator can send a client, no video generation required. Mirrors the shape (not the code) of
 * creator-service's real StoryboardClientReviewService.buildAnimatedPreview: same "pick the best
 * image per shot, drive timing off shot duration" idea, rebuilt against Shot/ShotImage instead of
 * the old CreatorAsset model. Image URLs are the normal 1-hour signed URLs (not offline-embedded/
 * watermarked base64 -- that's a follow-up, not built here); the page is meant to be viewed live,
 * re-requested if the link goes stale.
 */
@Service
public class AnimatedPreviewService {

    /** Preference order when a shot has more than one image kind -- the finished commercial frame
     * beats the planning sketches. */
    private static final List<ShotImageKind> KIND_PREFERENCE =
            List.of(ShotImageKind.PRODUCTION, ShotImageKind.STORYBOARD, ShotImageKind.LIGHTING, ShotImageKind.CAMERA_PLAN);

    private final ShotRepository shotRepository;
    private final ShotImageRepository shotImageRepository;
    private final ProjectService projectService;
    private final MinioClient minioClient;

    public AnimatedPreviewService(
            ShotRepository shotRepository, ShotImageRepository shotImageRepository, ProjectService projectService, MinioClient minioClient) {
        this.shotRepository = shotRepository;
        this.shotImageRepository = shotImageRepository;
        this.projectService = projectService;
        this.minioClient = minioClient;
    }

    @Transactional(readOnly = true)
    public String buildAnimatedPreview(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        if (shots.isEmpty()) {
            throw PreProductionException.badRequest("Project " + projectId + " has no shots yet");
        }

        String slides = shots.stream().map(this::renderSlide).collect(Collectors.joining("\n"));
        String thumbs = shots.stream().map(this::renderThumb).collect(Collectors.joining("\n"));
        return HTML_TEMPLATE.replace("{{SLIDES}}", slides).replace("{{THUMBS}}", thumbs);
    }

    private String renderSlide(Shot shot) {
        String imageUrl = bestImageUrl(shot);
        int durationMs = Math.max(2500, Math.min(8000, (shot.getDurationSeconds() == null ? 4 : shot.getDurationSeconds()) * 1000));
        String caption = escape((shot.getAction() == null ? "" : shot.getAction()));
        return "<div class=\"slide\" data-duration=\"" + durationMs + "\">"
                + (imageUrl != null ? "<img src=\"" + escape(imageUrl) + "\" alt=\"Shot " + shot.getShotNumber() + "\">" : "<div class=\"placeholder\">No image yet</div>")
                + "<div class=\"caption\"><span class=\"shot-num\">Shot " + shot.getShotNumber() + "</span> " + caption + "</div>"
                + "</div>";
    }

    private String renderThumb(Shot shot) {
        String imageUrl = bestImageUrl(shot);
        return "<div class=\"thumb\">" + (imageUrl != null ? "<img src=\"" + escape(imageUrl) + "\">" : "") + "</div>";
    }

    private String bestImageUrl(Shot shot) {
        Map<ShotImageKind, ShotImage> byKind = shotImageRepository.findByShotId(shot.getId()).stream()
                .collect(Collectors.toMap(ShotImage::getKind, i -> i, (a, b) -> a));
        ShotImage chosen = KIND_PREFERENCE.stream()
                .map(byKind::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return chosen == null ? null : signedUrl(chosen.getBucket(), chosen.getObjectKey());
    }

    private String signedUrl(String bucket, String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign preview image URL: " + ex.getMessage());
        }
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String HTML_TEMPLATE = """
            <!doctype html>
            <html>
            <head>
            <meta charset="utf-8">
            <title>Storyboard preview</title>
            <style>
              * { box-sizing: border-box; }
              body { margin: 0; background: #05070d; color: #fff; font-family: -apple-system, Segoe UI, sans-serif; }
              .stage { position: relative; width: 100vw; height: 78vh; overflow: hidden; background: #000; }
              .slide { position: absolute; inset: 0; opacity: 0; transition: opacity 0.6s ease; }
              .slide.active { opacity: 1; }
              .slide img { width: 100%; height: 100%; object-fit: cover; animation: kenburns linear forwards; animation-duration: inherit; }
              .slide.active img { animation-duration: var(--dur, 5s); }
              @keyframes kenburns { from { transform: scale(1.0); } to { transform: scale(1.08); } }
              .placeholder { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: #666; font-size: 14px; }
              .caption { position: absolute; left: 24px; bottom: 20px; right: 24px; font-size: 15px; font-weight: 600; text-shadow: 0 2px 8px rgba(0,0,0,0.8); }
              .shot-num { color: #b794f6; margin-right: 8px; }
              .controls { display: flex; align-items: center; justify-content: center; gap: 14px; padding: 14px; }
              .controls button { background: #6d3fd8; border: none; color: #fff; padding: 9px 16px; border-radius: 8px; font-weight: 700; cursor: pointer; font-size: 13px; }
              .thumbs { display: flex; gap: 6px; overflow-x: auto; padding: 10px 16px 20px; }
              .thumb { width: 56px; height: 56px; flex-shrink: 0; border-radius: 6px; overflow: hidden; opacity: 0.5; border: 2px solid transparent; }
              .thumb img { width: 100%; height: 100%; object-fit: cover; }
              .thumb.active { opacity: 1; border-color: #b794f6; }
            </style>
            </head>
            <body>
            <div class="stage" id="stage">
            {{SLIDES}}
            </div>
            <div class="controls">
              <button onclick="go(current-1)">&larr; Prev</button>
              <button id="playBtn" onclick="togglePlay()">Pause</button>
              <button onclick="go(current+1)">Next &rarr;</button>
            </div>
            <div class="thumbs" id="thumbs">
            {{THUMBS}}
            </div>
            <script>
              var slides = document.querySelectorAll('.slide');
              var thumbs = document.querySelectorAll('.thumb');
              var current = 0, timer = null, playing = true;
              function show(i) {
                current = (i + slides.length) % slides.length;
                slides.forEach(function(s, idx) {
                  s.classList.toggle('active', idx === current);
                  s.style.setProperty('--dur', (s.dataset.duration || 5000) + 'ms');
                });
                thumbs.forEach(function(t, idx) { t.classList.toggle('active', idx === current); });
              }
              function schedule() {
                clearTimeout(timer);
                if (!playing) return;
                var dur = parseInt(slides[current].dataset.duration || '5000', 10);
                timer = setTimeout(function() { show(current + 1); schedule(); }, dur);
              }
              function go(i) { show(i); schedule(); }
              function togglePlay() {
                playing = !playing;
                document.getElementById('playBtn').textContent = playing ? 'Pause' : 'Play';
                schedule();
              }
              thumbs.forEach(function(t, idx) { t.addEventListener('click', function() { go(idx); }); });
              show(0);
              schedule();
            </script>
            </body>
            </html>
            """;
}
