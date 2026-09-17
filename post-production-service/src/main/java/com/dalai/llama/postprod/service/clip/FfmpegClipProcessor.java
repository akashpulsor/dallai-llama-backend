package com.dalai.llama.postprod.service.clip;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Every ffmpeg operation this service performs on a clip, in one place.
 *
 * <p>The filters themselves are small; what is worth centralising is everything around them. Each of
 * these was learned from a specific failure and has to be applied identically everywhere:
 *
 * <ul>
 *   <li><b>apad before shortest.</b> {@code -shortest} alone ends the output at the SHORTER stream,
 *       so a 3.6s take on a 4s picture cuts the picture to 3.6s. apad runs the audio on as silence
 *       and shortest then stops at the picture.</li>
 *   <li><b>Silence is a track, not an absent one.</b> The film is assembled with a concat filter
 *       that demands an audio stream on every input, so a clip with no track passes here and fails
 *       the whole assembly later, far from the cause.</li>
 *   <li><b>The whole of ffmpeg's output on failure.</b> ffmpeg reports an error partway through and
 *       then carries on printing progress, so the last line is usually a stats line -- raising only
 *       that threw the cause away and made these failures undiagnosable for days.</li>
 * </ul>
 */
@Slf4j
@Component
public class FfmpegClipProcessor {

    private final int timeoutSeconds;

    public FfmpegClipProcessor(@Value("${post-production.ffmpeg.timeout-seconds:600}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * The picture untouched, carrying {@code audio} instead of whatever it came with.
     *
     * <p>{@code -map 0:v -map 1:a} takes the picture from one input and the sound from the other,
     * which is what DROPS the original rather than mixing the two: a line the model half-spoke
     * underneath the line it should have spoken is worse than either alone. The video is
     * stream-copied, so the picture is bit-identical to what was generated and paid for.
     */
    public void replaceAudio(Path clip, Path audio, Path output) {
        run(List.of("ffmpeg", "-y", "-i", clip.toString(), "-i", audio.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                "-af", "apad", "-shortest", output.toString()));
    }

    /** The picture untouched, carrying silence instead of whatever it decided to say. */
    public void stripAudio(Path clip, Path output) {
        run(List.of("ffmpeg", "-y", "-i", clip.toString(),
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                "-shortest", output.toString()));
    }

    /**
     * Joins clips in order into one film, at one size.
     *
     * <p>Re-encoded rather than stream-copied, and every input scaled and padded to the target: shots
     * come from different renders, concat refuses mismatched sizes outright, and {@code -c copy}
     * across mismatched streams produces a file that plays for two seconds and stops. Padded rather
     * than cropped, because losing the edge of a frame to make a join work is not a trade anyone
     * asked for.
     */
    public void concat(List<Path> clips, int width, int height, Path output) {
        if (clips.isEmpty()) {
            throw new ClipProcessingException("There are no shots to join");
        }
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y"));
        for (Path clip : clips) {
            command.add("-i");
            command.add(clip.toString());
        }
        String size = width + ":" + height;
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            filter.append("[").append(i).append(":v]scale=").append(size)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(size).append(":(ow-iw)/2:(oh-ih)/2,setsar=1[v")
                    .append(i).append("];");
            // Resampled with a common time base: shots recorded at different sample rates otherwise
            // drift against the picture a little further with every join.
            filter.append("[").append(i).append(":a]aresample=async=1:first_pts=0[a")
                    .append(i).append("];");
        }
        for (int i = 0; i < clips.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append("]");
        }
        filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]");
        command.addAll(List.of("-filter_complex", filter.toString(),
                "-map", "[v]", "-map", "[a]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-movflags", "+faststart", output.toString()));
        run(command);
    }

    /**
     * What the file actually is, measured.
     *
     * <p>Never throws for an unreadable file: an unplayable probe is an answer, and the caller
     * decides what to do about it. Every cut is probed before it is stored, because a file with no
     * picture in it used to be uploaded and promoted exactly like a good one -- which is how a
     * finished shot came back with no video at all.
     */
    public ClipProbe probe(Path file) {
        String streams = capture(List.of("ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type,width,height",
                "-of", "default=noprint_wrappers=1", file.toString()));
        String duration = capture(List.of("ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString()));
        boolean hasVideo = streams.contains("codec_type=video");
        boolean hasAudio = streams.contains("codec_type=audio");
        BigDecimal seconds = null;
        try {
            double parsed = Double.parseDouble(duration.lines().findFirst().orElse("-1").trim());
            if (parsed > 0) {
                seconds = BigDecimal.valueOf(Math.round(parsed * 1000) / 1000.0);
            }
        } catch (NumberFormatException ignored) {
            // Not a video, or not one ffprobe can read. hasVideo already says so.
        }
        return new ClipProbe(hasVideo, hasAudio, seconds, firstInt(streams, "width="), firstInt(streams, "height="));
    }

    private static Integer firstInt(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key))
                .map(line -> line.substring(key.length()).trim())
                .map(value -> {
                    try {
                        return Integer.valueOf(value);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** A scratch directory for one operation, deleted whatever happens. */
    public Path createWorkDir(String label) {
        try {
            return Files.createTempDirectory("clip-" + label + "-");
        } catch (Exception ex) {
            throw new ClipProcessingException("Could not create a working directory", ex);
        }
    }

    public void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Scratch space; a leftover file is not worth failing a finished cut over.
                }
            });
        } catch (Exception ignored) {
            // As above.
        }
    }

    private String capture(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(60, TimeUnit.SECONDS);
            return output;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private void run(List<String> command) {
        log.debug("ffmpeg {}", String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ClipProcessingException("ffmpeg timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg failed exit={} command={} output={}",
                        process.exitValue(), String.join(" ", command), output);
                throw new ClipProcessingException("ffmpeg failed: " + reason(output));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ClipProcessingException("Interrupted while running ffmpeg");
        } catch (ClipProcessingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ClipProcessingException("Could not run ffmpeg: " + ex.getMessage(), ex);
        }
    }

    /** The lines that name a failure, for a message a creator reads. */
    private static String reason(String output) {
        List<String> notable = output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error") || lower.contains("invalid")
                            || lower.contains("no such") || lower.contains("unable")
                            || lower.contains("could not") || lower.contains("not supported");
                })
                .distinct()
                .limit(4)
                .toList();
        if (!notable.isEmpty()) {
            return String.join("; ", notable);
        }
        return output.lines().map(String::strip).filter(line -> !line.isBlank())
                .reduce((a, b) -> b).orElse("no output");
    }
}
