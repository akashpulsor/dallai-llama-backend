package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.entity.ShotPromptReference;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lays a shot's background music bed under its finished video.
 *
 * <p>The bed was already being generated, paid for and stored as a BACKGROUND_MUSIC reference, and
 * then dropped -- nothing ever mixed it in, so every export was missing the track the creator had
 * asked for and been charged for.
 *
 * <p>Done with the container's own ffmpeg rather than fal's hosted ffmpeg apps, because level
 * control is the whole problem. {@code fal-ai/ffmpeg-api/merge-audio-video} lays exactly one track
 * and {@code .../compose} and {@code .../merge-audios} take several but expose no volume, gain or
 * weight -- mixing a music bed against dialogue at unity there would bury the dialogue. Locally,
 * {@code amix} with an explicit {@code volume} filter puts the bed where a bed belongs. The image
 * already carries ffmpeg for {@link FinalRenderService}'s concat, so this costs nothing extra.
 *
 * <p>Best-effort by design, exactly like auto-dub: a shot that fails to gain its music is still a
 * finished shot, and losing the whole render over a background track would be the wrong trade.
 */
@Slf4j
@Service
public class BackgroundMusicMixService {

    private final ShotPromptReferenceRepository shotPromptReferenceRepository;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final double musicVolume;
    private final long ffmpegTimeoutSeconds;

    public BackgroundMusicMixService(
            ShotPromptReferenceRepository shotPromptReferenceRepository,
            VideoAssetPersistenceService assetPersistenceService,
            /** Bed level relative to whatever is already on the video. 0.18 is a conventional
             * under-dialogue bed -- audible, never competing. Tunable without a redeploy because
             * the right number is a taste judgement, not an engineering one. */
            @Value("${video-gen.background-music.volume:0.18}") double musicVolume,
            @Value("${video-gen.background-music.ffmpeg-timeout-seconds:300}") long ffmpegTimeoutSeconds
    ) {
        this.shotPromptReferenceRepository = shotPromptReferenceRepository;
        this.assetPersistenceService = assetPersistenceService;
        this.musicVolume = musicVolume;
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
    }

    /**
     * @param videoUrl the shot's finished video, already dubbed if it was going to be
     * @return the mixed video's URL, or {@code videoUrl} unchanged when this shot has no music bed
     *         or the mix could not be done
     */
    public String mixIfPresent(UUID jobId, UUID promptId, String videoUrl) {
        Optional<ShotPromptReference> music = findMusicReference(promptId);
        if (music.isEmpty()) {
            return videoUrl;
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("music-mix-" + jobId);
            Path videoPath = workDir.resolve("video.mp4");
            Path musicPath = workDir.resolve("music.mp3");
            Path output = workDir.resolve("mixed.mp4");

            download(videoUrl, videoPath);
            assetPersistenceService.downloadTo(music.get().getBucket(), music.get().getObjectKey(), musicPath);

            runFfmpeg(videoPath, musicPath, output);
            if (!Files.exists(output) || Files.size(output) == 0) {
                log.warn("Music mix produced no output jobId={} -- keeping the unmixed video", jobId);
                return videoUrl;
            }

            // Stored beside the shot's other output rather than overwriting it: the unmixed take
            // stays retrievable if the bed turns out to be wrong for the shot.
            VideoAssetPersistenceService.PersistedAsset persisted = assetPersistenceService.uploadFile(
                    music.get().getBucket(), "mixed/%s.mp4".formatted(jobId), output);
            String mixedUrl = assetPersistenceService.presignedUrl(persisted.bucket(), persisted.objectKey());
            log.info("Mixed background music jobId={} promptId={} volume={}", jobId, promptId, musicVolume);
            return mixedUrl;
        } catch (Exception ex) {
            log.warn("Could not mix background music jobId={} -- keeping the unmixed video: {}", jobId, ex.getMessage());
            return videoUrl;
        } finally {
            deleteQuietly(workDir);
        }
    }

    private Optional<ShotPromptReference> findMusicReference(UUID promptId) {
        if (promptId == null) {
            return Optional.empty();
        }
        return shotPromptReferenceRepository.findByPromptId(promptId).stream()
                .filter(ref -> ref.getRefKind() == ReferenceKind.BACKGROUND_MUSIC)
                .filter(ref -> ref.getBucket() != null && ref.getObjectKey() != null)
                .min(Comparator.comparing(ShotPromptReference::getSlotIndex));
    }

    /**
     * {@code amix} across the video's own audio and the attenuated bed.
     *
     * <p>{@code duration=first} ends the mix with the video, so a bed longer than the shot is cut
     * rather than extending it; {@code apad} covers the other direction, a bed shorter than the
     * shot, which would otherwise end the audio stream early and truncate the clip.
     *
     * <p>{@code 0:a?} is optional on purpose: a shot whose dialogue was never dubbed has no audio
     * stream at all, and without the {@code ?} ffmpeg fails outright instead of laying the bed
     * onto a silent video -- which is precisely the case where a music bed matters most.
     */
    private void runFfmpeg(Path video, Path music, Path output) {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y",
                "-i", video.toString(),
                "-i", music.toString(),
                "-filter_complex",
                "[1:a]volume=%s,apad[bed];[0:a?][bed]amix=inputs=2:duration=first:dropout_transition=0[a]"
                        .formatted(musicVolume),
                "-map", "0:v",
                "-map", "[a]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-shortest",
                "-movflags", "+faststart",
                output.toString()
        ));
        runProcess(command);
    }

    private void runProcess(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!process.waitFor(ffmpegTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out after " + ffmpegTimeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg exited " + process.exitValue() + ": " + tail(out.toString()));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("ffmpeg could not be started: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for ffmpeg", ex);
        }
    }

    private void download(String url, Path destination) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download failed status=" + response.statusCode() + " url=" + url);
        }
    }

    /** ffmpeg's failure reason is in its last lines; the rest is banner and progress noise. */
    private String tail(String output) {
        String[] lines = output.strip().split("\n");
        int from = Math.max(0, lines.length - 5);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp dir; the pod's filesystem goes away with it.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
