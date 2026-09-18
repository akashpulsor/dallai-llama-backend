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
     * What a concat needs to know about one input, and nothing else.
     *
     * <p>Equality across every input is exactly the condition for joining by stream copy. Geometry
     * is only one of five: two clips at the same resolution still cannot be copied together if they
     * differ in codec, pixel format, frame rate, or audio -- and a silent shot among thirteen with
     * sound breaks a copy join outright, which is why {@code audio} being absent is recorded rather
     * than ignored.
     */
    record ConcatInput(String videoCodec, String pixelFormat, String frameRate, int width, int height,
                       String audioCodec, String sampleRate, String channels) {
    }

    /** Reads the handful of stream properties a copy-join depends on. Over http this pulls headers,
     * not the clip. */
    ConcatInput probeConcatInput(String input) {
        String out = capture(List.of("ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,width,height,pix_fmt,r_frame_rate,sample_rate,channels",
                "-of", "default=noprint_wrappers=1", input));
        String videoCodec = null, pixelFormat = null, frameRate = null, audioCodec = null,
                sampleRate = null, channels = null;
        int width = 0, height = 0;
        String type = null;
        for (String line : out.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "codec_type" -> type = value;
                case "codec_name" -> {
                    if ("video".equals(type)) {
                        videoCodec = value;
                    } else if ("audio".equals(type)) {
                        audioCodec = value;
                    }
                }
                case "pix_fmt" -> pixelFormat = value;
                case "r_frame_rate" -> frameRate = value;
                case "sample_rate" -> sampleRate = value;
                case "channels" -> channels = value;
                case "width" -> width = parseIntOrZero(value);
                case "height" -> height = parseIntOrZero(value);
                default -> { }
            }
        }
        return new ConcatInput(videoCodec, pixelFormat, frameRate, width, height,
                audioCodec, sampleRate, channels);
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Whether these inputs can simply be stapled together.
     *
     * <p>Every property has to match, and the result has to already be the size the film wants --
     * a copy join cannot resize, so uniform inputs at the wrong geometry still have to be encoded.
     */
    static boolean canCopyJoin(List<ConcatInput> inputs, int width, int height) {
        if (inputs.isEmpty()) {
            return false;
        }
        ConcatInput first = inputs.get(0);
        if (first.videoCodec() == null || first.width() != width || first.height() != height) {
            return false;
        }
        return inputs.stream().allMatch(first::equals);
    }

    /**
     * Joins clips in order into one film, at one size.
     *
     * <p>Copied when it can be and encoded when it must be, decided per join by probing the inputs.
     * A copy join cannot resize or convert, so it is available only when every shot already agrees
     * on codec, pixel format, frame rate, geometry and audio -- which is a question about the models
     * that produced them, and therefore a question to measure rather than to answer in advance.
     *
     * <p>When it is not available, every input is scaled and PADDED to the target. Padded rather
     * than cropped, because losing the edge of a frame to make a join work is not a trade anyone
     * asked for.
     */
    public void concat(List<String> clips, int width, int height, Path output) {
        if (clips.isEmpty()) {
            throw new ClipProcessingException("There are no shots to join");
        }
        // Encoding is a consequence of having to change the pixels, not a decision taken on its own.
        // When every shot already agrees on codec, pixel format, frame rate, geometry and audio,
        // nothing needs changing and the shots can simply be stapled together: seconds instead of
        // minutes, no quality lost to a second generation, and -- because nothing is decoded -- none
        // of the memory that holding thirteen decoders open costs.
        //
        // Measured rather than assumed, per join. Clips come from different models, and which model
        // produced a shot is not something this service knows or should have to track.
        List<ConcatInput> inputs = clips.stream().map(this::probeConcatInput).toList();
        if (canCopyJoin(inputs, width, height)) {
            log.info("Joining {} shots by stream copy -- every input already matches", clips.size());
            copyJoin(clips, output);
            return;
        }
        log.info("Joining {} shots by re-encode -- inputs differ: {}", clips.size(), describeDifference(inputs));
        encodeJoin(clips, width, height, output);
    }

    /** Why the fast path was not available, for the operator wondering where the minutes went. */
    private static String describeDifference(List<ConcatInput> inputs) {
        ConcatInput first = inputs.get(0);
        return inputs.stream().filter(input -> !first.equals(input)).findFirst()
                .map(other -> "%s vs %s".formatted(first, other))
                .orElse("geometry is not the film's target size");
    }

    /**
     * The fast path: no decode, no encode, just one container from many.
     *
     * <p>The concat DEMUXER, not the filter. It reads a list of inputs and copies their packets
     * through, which is only valid because every input was just checked to be identical.
     */
    private void copyJoin(List<String> clips, Path output) {
        Path listFile = output.getParent().resolve("inputs.txt");
        StringBuilder list = new StringBuilder();
        for (String clip : clips) {
            list.append("file '").append(clip).append("'").append(System.lineSeparator());
        }
        try {
            Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ClipProcessingException("Could not write the join list", ex);
        }
        run(List.of("ffmpeg", "-y",
                // The list holds signed https URLs, so the demuxer has to be allowed to open them;
                // -safe 0 because those paths are not the "safe" relative filenames it wants.
                "-protocol_whitelist", "file,http,https,tcp,tls,crypto",
                "-safe", "0",
                "-f", "concat", "-i", listFile.toString(),
                "-c", "copy", "-movflags", "+faststart", output.toString()));
    }

    /** The slow path: normalise every shot to one geometry and encode the result once. */
    private void encodeJoin(List<String> clips, int width, int height, Path output) {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y"));
        for (String clip : clips) {
            // Inputs are signed object-store URLs, read straight from MinIO. Pulling all thirteen
            // to disk first only to hand them back to ffmpeg was a serial download of the entire
            // film before a single frame could be encoded, plus the disk to hold it.
            //
            // The reconnect options are what make that safe to rely on: without them a dropped
            // connection part-way through kills a join that may already be minutes in. They are
            // http-protocol options, so they go on http inputs only -- ffmpeg rejects them outright
            // on a local path.
            if (clip.startsWith("http://") || clip.startsWith("https://")) {
                command.addAll(List.of(
                        "-reconnect", "1",
                        "-reconnect_streamed", "1",
                        "-reconnect_on_network_error", "1",
                        "-reconnect_delay_max", "10"));
            }
            command.add("-i");
            command.add(clip);
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
