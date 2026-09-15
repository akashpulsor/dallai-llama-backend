package com.dalai.llama.videogen.service.dialoguefit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * How long a piece of media actually runs, according to ffprobe.
 *
 * <p>One copy of this. It had been written three times over -- once in the orchestrator against a
 * presigned URL, once in {@code BeatDubbingService} against a downloaded file, and the estimate it
 * replaces in a third place -- with three different timeouts and three slightly different ways of
 * reading "unknown". Duration is the number every part of the dialogue-fit decision turns on, so it
 * is worth having exactly one answer to it.
 *
 * <p>Returns a negative number rather than throwing, for every failure: a missing ffprobe, a URL
 * that will not open, output that will not parse. Callers read that as "unknown" and fall back to
 * what they would have done without a measurement, because not knowing how long the audio is must
 * never be what stops a shot from being generated.
 */
@Slf4j
@Component
public class AudioDurationProbe {

    /** Negative means unknown -- ffprobe could not tell us. */
    public static final double UNKNOWN = -1;

    private final long timeoutSeconds;

    public AudioDurationProbe(@Value("${video-gen.dialogue-fit.probe-timeout-seconds:60}") long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Probes a URL in place -- ffprobe reads only the header, so nothing is downloaded whole. */
    public double probeUrl(String url) {
        return url == null || url.isBlank() ? UNKNOWN : probe(url);
    }

    public double probeFile(Path file) {
        return file == null || !Files.exists(file) ? UNKNOWN : probe(file.toString());
    }

    /** For audio that exists only as bytes in hand -- ffprobe needs a path, so this lends it one. */
    public double probeBytes(byte[] bytes, String suffix) {
        if (bytes == null || bytes.length == 0) {
            return UNKNOWN;
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("probe-", suffix == null || suffix.isBlank() ? ".bin" : suffix);
            Files.write(temp, bytes);
            return probeFile(temp);
        } catch (Exception ex) {
            log.warn("Could not measure audio length from bytes: {}", ex.getMessage());
            return UNKNOWN;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // A leftover temp file is not worth failing a measurement over.
                }
            }
        }
    }

    private double probe(String target) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", target))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffprobe timed out measuring audio length");
                return UNKNOWN;
            }
            double seconds = Double.parseDouble(output.lines().findFirst().orElse("-1").trim());
            // ffprobe reports N/A as a non-numeric line on some containers, and 0 on a truncated
            // file. Neither is a length, and treating 0 as one would make a silent take look like
            // a perfect fit.
            return seconds > 0 ? seconds : UNKNOWN;
        } catch (Exception ex) {
            log.warn("Could not measure audio length: {}", ex.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
            return UNKNOWN;
        }
    }
}
