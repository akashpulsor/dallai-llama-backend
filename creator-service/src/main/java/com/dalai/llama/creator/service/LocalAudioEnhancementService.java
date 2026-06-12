package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LocalAudioEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(LocalAudioEnhancementService.class);
    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(30);

    private final AssetStorageService assetStorageService;
    private final CreatorProperties properties;

    public LocalAudioEnhancementService(
            AssetStorageService assetStorageService,
            CreatorProperties properties
    ) {
        this.assetStorageService = assetStorageService;
        this.properties = properties;
    }

    public RenderedAudio render(
            UUID scriptId,
            int shotNumber,
            UUID takeId,
            String sourceBucket,
            String sourceObjectKey,
            String sourceContentType,
            Map<String, Object> controls
    ) {
        if (isBlank(sourceBucket) || isBlank(sourceObjectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded take media is missing storage location.");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-audio-enhance-");
            Path sourcePath = workspace.resolve("source" + extensionFor(sourceContentType, sourceObjectKey));
            log.info(
                    "Audio enhancement started takeId={} scriptId={} shotNumber={} sourceContentType={} sourceObjectKey={} workspace={}",
                    takeId,
                    scriptId,
                    shotNumber,
                    sourceContentType,
                    sourceObjectKey,
                    workspace
            );
            assetStorageService.downloadObjectToPath(sourceBucket, sourceObjectKey, sourcePath);
            log.info(
                    "Audio enhancement source downloaded takeId={} sourceBytes={}",
                    takeId,
                    Files.size(sourcePath)
            );

            Path preparedSpeechPath = workspace.resolve("speech-48k-mono.wav");
            log.info("Audio enhancement extracting speech WAV takeId={} targetSampleRate=48000 channelLayout=mono", takeId);
            String extractionLog = run(extractSpeechWavCommand(sourcePath, preparedSpeechPath));
            log.info(
                    "Audio enhancement speech WAV extracted takeId={} speechBytes={}",
                    takeId,
                    Files.size(preparedSpeechPath)
            );
            NeuralDenoiseResult neuralDenoise = runNeuralDenoise(preparedSpeechPath, workspace, controls == null ? Map.of() : controls);
            Path polishInputPath = neuralDenoise.outputPath() == null ? preparedSpeechPath : neuralDenoise.outputPath();

            Path outputPath = workspace.resolve("audio-enhance-" + takeId + ".wav");
            AudioEnhancementCommand command = ffmpegCommand(polishInputPath, outputPath, controls == null ? Map.of() : controls);
            log.info(
                    "Audio enhancement final FFmpeg polish starting takeId={} neuralDenoiseUsed={} polishInput={} polishInputBytes={} filterChars={}",
                    takeId,
                    neuralDenoise.used(),
                    polishInputPath,
                    Files.size(polishInputPath),
                    command.filterChain().length()
            );
            String ffmpegLog = run(command.command());
            byte[] bytes = Files.readAllBytes(outputPath);
            log.info(
                    "Audio enhancement final FFmpeg polish completed takeId={} outputBytes={}",
                    takeId,
                    bytes.length
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("renderer", "local_ffmpeg");
            metadata.put("model", neuralDenoise.used() ? "neural-denoise-plus-ffmpeg-audio-enhance-v1" : "ffmpeg-audio-enhance-v1");
            metadata.put("noiseSuppressionProfile", neuralDenoise.used() ? "neural_voice_first_ffmpeg_v1" : "voice_first_ffmpeg_v2");
            metadata.put("neuralDenoise", neuralDenoise.metadata());
            metadata.put("ffmpegFilterChain", command.filterChain());
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("shotNumber", shotNumber);
            metadata.put("takeId", takeId == null ? null : takeId.toString());
            metadata.put("sourceContentType", sourceContentType);
            metadata.put("controls", controls == null ? Map.of() : controls);
            metadata.put("renderedAt", OffsetDateTime.now().toString());
            metadata.put("audioExtractionLogTail", tail(extractionLog, 1200));
            metadata.put("ffmpegLogTail", tail(ffmpegLog, 1800));
            metadata.put("audioFixes", List.of(
                    neuralDenoise.used() ? "neural_speech_denoise_applied" : "neural_speech_denoise_not_available",
                    "adaptive_fft_background_noise_suppression",
                    "fan_hum_harmonic_notches",
                    "pause_gate_reduces_room_and_fan_wash",
                    "speech_clarity_improved",
                    "light_dereverb_if_needed",
                    "loudness_normalized",
                    "voice_texture_preserved"
            ));
            return new RenderedAudio(
                    "audio-enhance-" + takeId + ".wav",
                    "audio/wav",
                    bytes,
                    metadata
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not enhance recorded audio.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private List<String> extractSpeechWavCommand(Path sourcePath, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(sourcePath.toString());
        command.add("-vn");
        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add("48000");
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(outputPath.toString());
        return command;
    }

    private NeuralDenoiseResult runNeuralDenoise(Path inputPath, Path workspace, Map<String, Object> controls) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean enabled = booleanControl(controls, "neuralDenoiseEnabled", properties.getAi().isAudioNeuralDenoiseEnabled());
        String requestedProvider = stringControl(controls, "neuralDenoiseProvider", properties.getAi().getAudioNeuralDenoiseProvider());
        metadata.put("enabled", enabled);
        metadata.put("requestedProvider", requestedProvider);
        metadata.put("fallbackAllowed", !properties.getAi().isAudioNeuralDenoiseFailOnMissing());
        if (!enabled) {
            metadata.put("status", "SKIPPED");
            metadata.put("reason", "disabled");
            return new NeuralDenoiseResult(false, null, metadata);
        }

        List<String> providers = neuralDenoiseProviders(requestedProvider);
        List<Map<String, Object>> attempts = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (String provider : providers) {
            try {
                NeuralDenoiseResult result = switch (provider) {
                    case "deepfilter" -> runDeepFilter(inputPath, workspace, controls);
                    case "rnnoise" -> runRnnoise(inputPath, workspace);
                    case "ffmpeg_arnndn" -> runFfmpegArnndn(inputPath, workspace, controls);
                    default -> throw new IllegalArgumentException("Unsupported neural denoise provider: " + provider);
                };
                metadata.putAll(result.metadata());
                metadata.put("status", "APPLIED");
                metadata.put("providerUsed", provider);
                metadata.put("attempts", attempts);
                log.info("Audio neural denoise applied provider={} outputPath={}", provider, result.outputPath());
                return new NeuralDenoiseResult(true, result.outputPath(), metadata);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                attempts.add(Map.of(
                        "provider", provider,
                        "status", "FAILED",
                        "message", tail(defaultString(ex.getMessage(), ex.getClass().getSimpleName()), 600)
                ));
                log.warn("Audio neural denoise provider failed provider={} reason={}", provider, tail(defaultString(ex.getMessage(), ex.getClass().getSimpleName()), 600));
            }
        }

        if (properties.getAi().isAudioNeuralDenoiseFailOnMissing() && lastFailure != null) {
            throw lastFailure;
        }
        metadata.put("status", "FALLBACK_TO_FFMPEG");
        metadata.put("attempts", attempts);
        metadata.put("reason", lastFailure == null ? "no_provider_attempted" : tail(defaultString(lastFailure.getMessage(), lastFailure.getClass().getSimpleName()), 800));
        return new NeuralDenoiseResult(false, null, metadata);
    }

    private List<String> neuralDenoiseProviders(String requestedProvider) {
        String normalized = value(requestedProvider).toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.isBlank() || normalized.equals("auto")) {
            return List.of("deepfilter", "rnnoise", "ffmpeg_arnndn");
        }
        if (normalized.equals("deep_filter") || normalized.equals("deepfilternet")) {
            return List.of("deepfilter", "rnnoise", "ffmpeg_arnndn");
        }
        if (normalized.equals("rnnoise")) {
            return List.of("rnnoise", "deepfilter", "ffmpeg_arnndn");
        }
        if (normalized.equals("arnndn") || normalized.equals("ffmpeg_rnn")) {
            return List.of("ffmpeg_arnndn", "deepfilter", "rnnoise");
        }
        return List.of(normalized);
    }

    private NeuralDenoiseResult runDeepFilter(Path inputPath, Path workspace, Map<String, Object> controls) {
        String commandName = stringControl(controls, "deepFilterCommand", properties.getAi().getDeepFilterCommand());
        Path outputDirectory = workspace.resolve("deepfilter-output");
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create DeepFilter output directory.", ex);
        }
        List<String> command = new ArrayList<>();
        command.add(commandName);
        if (booleanControl(controls, "deepFilterPostFilterEnabled", properties.getAi().isDeepFilterPostFilterEnabled())) {
            command.add("--pf");
        }
        String modelPath = stringControl(controls, "deepFilterModelPath", properties.getAi().getDeepFilterModelPath());
        if (!modelPath.isBlank()) {
            command.add("-m");
            command.add(modelPath);
        }
        command.add("-o");
        command.add(outputDirectory.toString());
        command.add(inputPath.toString());
        String providerLog = run(command);
        Path outputPath = newestWav(outputDirectory);
        if (outputPath == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DeepFilter completed without producing a WAV file.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "deepfilter");
        metadata.put("command", commandName);
        metadata.put("postFilterEnabled", booleanControl(controls, "deepFilterPostFilterEnabled", properties.getAi().isDeepFilterPostFilterEnabled()));
        metadata.put("modelPathConfigured", !modelPath.isBlank());
        metadata.put("outputFileName", outputPath.getFileName().toString());
        metadata.put("providerLogTail", tail(providerLog, 1400));
        return new NeuralDenoiseResult(true, outputPath, metadata);
    }

    private NeuralDenoiseResult runRnnoise(Path inputPath, Path workspace) {
        String commandName = properties.getAi().getRnnoiseCommand();
        Path rawInput = workspace.resolve("rnnoise-input.raw");
        Path rawOutput = workspace.resolve("rnnoise-output.raw");
        Path wavOutput = workspace.resolve("rnnoise-output.wav");
        String inputLog = run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-y",
                "-i", inputPath.toString(),
                "-f", "s16le",
                "-ac", "1",
                "-ar", "48000",
                rawInput.toString()
        ));
        String providerLog = run(List.of(commandName, rawInput.toString(), rawOutput.toString()));
        String outputLog = run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-y",
                "-f", "s16le",
                "-ac", "1",
                "-ar", "48000",
                "-i", rawOutput.toString(),
                "-c:a", "pcm_s16le",
                wavOutput.toString()
        ));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "rnnoise");
        metadata.put("command", commandName);
        metadata.put("inputConversionLogTail", tail(inputLog, 500));
        metadata.put("providerLogTail", tail(providerLog, 1000));
        metadata.put("outputConversionLogTail", tail(outputLog, 500));
        return new NeuralDenoiseResult(true, wavOutput, metadata);
    }

    private NeuralDenoiseResult runFfmpegArnndn(Path inputPath, Path workspace, Map<String, Object> controls) {
        String modelPath = stringControl(controls, "ffmpegArnndnModelPath", properties.getAi().getFfmpegArnndnModelPath());
        if (modelPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FFmpeg arnndn model path is not configured.");
        }
        double mix = clamp(number(controls.get("arnndnMix"), 1.0), -1.0, 1.0);
        Path outputPath = workspace.resolve("arnndn-output.wav");
        String filter = "arnndn=m=" + modelPath + ":mix=" + format(mix);
        String providerLog = run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-y",
                "-i", inputPath.toString(),
                "-af", filter,
                "-c:a", "pcm_s16le",
                outputPath.toString()
        ));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "ffmpeg_arnndn");
        metadata.put("modelPath", modelPath);
        metadata.put("mix", mix);
        metadata.put("filter", filter);
        metadata.put("providerLogTail", tail(providerLog, 1000));
        return new NeuralDenoiseResult(true, outputPath, metadata);
    }

    private AudioEnhancementCommand ffmpegCommand(Path sourcePath, Path outputPath, Map<String, Object> controls) {
        double noise = clamp(number(controls.get("noiseReductionStrength"), 0.78), 0.0, 1.0);
        double clarity = clamp(number(controls.get("clarityBoost"), 0.62), 0.0, 1.0);
        double deReverb = clamp(number(controls.get("deReverbStrength"), 0.30), 0.0, 1.0);
        double lufs = clamp(number(controls.get("loudnessTargetLufs"), -14.0), -24.0, -10.0);
        boolean fanNoiseRemoval = booleanValue(controls.get("fanNoiseRemoval"), true);
        boolean preserveRoomTone = booleanValue(controls.get("preserveRoomTone"), false);
        double noiseFloor = -30.0 - (noise * 12.0);
        double noiseReductionDb = 12.0 + (noise * 12.0);
        double secondPassNoiseFloor = noiseFloor - 6.0;
        double secondPassNoiseReductionDb = 5.0 + (noise * 5.0);
        double gateThreshold = preserveRoomTone ? 0.006 + (noise * 0.006) : 0.010 + (noise * 0.014);
        double gateRatio = preserveRoomTone ? 1.4 + (noise * 1.0) : 2.0 + (noise * 2.2);
        double highShelfGain = 1.0 + (clarity * 0.22);
        double mudCutGain = -1.0 - (deReverb * 2.2);
        double boxCutGain = -0.3 - (deReverb * 1.2);
        List<String> filters = new ArrayList<>();
        filters.add("highpass=f=" + format(preserveRoomTone ? 75.0 : 95.0));
        filters.add("lowpass=f=" + format(preserveRoomTone ? 12000.0 : 10500.0));
        if (fanNoiseRemoval) {
            filters.add("equalizer=f=50:t=q:w=10:g=-10");
            filters.add("equalizer=f=60:t=q:w=10:g=-10");
            filters.add("equalizer=f=100:t=q:w=8:g=-5");
            filters.add("equalizer=f=120:t=q:w=8:g=-7");
            filters.add("equalizer=f=150:t=q:w=7:g=-4");
            filters.add("equalizer=f=180:t=q:w=7:g=-5");
            filters.add("equalizer=f=240:t=q:w=5:g=-4");
            filters.add("equalizer=f=300:t=q:w=4:g=-2.5");
            filters.add("equalizer=f=360:t=q:w=4:g=-2.5");
        }
        filters.add("afftdn=nr=" + format(noiseReductionDb) + ":nf=" + format(noiseFloor) + ":tn=1");
        filters.add("agate=threshold=" + format(gateThreshold) + ":ratio=" + format(gateRatio) + ":attack=8:release=180:makeup=1");
        filters.add("afftdn=nr=" + format(secondPassNoiseReductionDb) + ":nf=" + format(secondPassNoiseFloor));
        filters.add("equalizer=f=240:t=q:w=1.0:g=" + format(mudCutGain));
        filters.add("equalizer=f=650:t=q:w=1.0:g=" + format(boxCutGain));
        filters.add("equalizer=f=3200:t=q:w=1.1:g=" + format(clarity * 2.0));
        filters.add("equalizer=f=7200:t=q:w=1.0:g=" + format(clarity * 0.9));
        filters.add("acompressor=threshold=-20dB:ratio=" + format(1.5 + clarity * 0.6) + ":attack=8:release=100:makeup=" + format(highShelfGain));
        filters.add("loudnorm=I=" + format(lufs) + ":TP=-1.2:LRA=10");
        filters.add("alimiter=limit=0.95");
        filters.add("aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo");
        String filter = String.join(",", filters);
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(sourcePath.toString());
        command.add("-vn");
        command.add("-af");
        command.add(filter);
        command.add("-ac");
        command.add("2");
        command.add("-ar");
        command.add("48000");
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(outputPath.toString());
        return new AudioEnhancementCommand(command, filter);
    }

    private String run(List<String> command) {
        String label = commandLabel(command);
        Path outputLog = null;
        long startedAt = System.nanoTime();
        try {
            outputLog = Files.createTempFile("creator-audio-command-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputLog.toFile())
                    .start();
            log.info(
                    "Audio command started label={} timeoutSeconds={} commandPreview=\"{}\"",
                    label,
                    FFMPEG_TIMEOUT.toSeconds(),
                    commandPreview(command, 900)
            );
            boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = readCommandLog(outputLog);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (!finished) {
                process.destroyForcibly();
                log.error(
                        "Audio command timed out label={} elapsedMs={} logTail=\"{}\"",
                        label,
                        elapsedMs,
                        tail(output, 1200)
                );
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, label + " audio enhancement timed out.");
            }
            if (process.exitValue() != 0) {
                log.error(
                        "Audio command failed label={} exitCode={} elapsedMs={} logTail=\"{}\"",
                        label,
                        process.exitValue(),
                        elapsedMs,
                        tail(output, 1200)
                );
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, label + " audio enhancement failed: " + tail(output, 3000));
            }
            log.info(
                    "Audio command completed label={} elapsedMs={} outputChars={}",
                    label,
                    elapsedMs,
                    output.length()
            );
            return output;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, label + " is not available for local audio enhancement.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, label + " audio enhancement was interrupted.", ex);
        } finally {
            if (outputLog != null) {
                try {
                    Files.deleteIfExists(outputLog);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String readCommandLog(Path outputLog) {
        if (outputLog == null || !Files.exists(outputLog)) {
            return "";
        }
        try {
            return Files.readString(outputLog);
        } catch (IOException ex) {
            return "Could not read audio command log: " + ex.getMessage();
        }
    }

    private String commandPreview(List<String> command, int maxLength) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        List<String> safe = new ArrayList<>();
        for (String item : command) {
            if (item == null) {
                continue;
            }
            safe.add(item.replace('\\', '/'));
        }
        return tail(String.join(" ", safe), maxLength);
    }

    private String commandLabel(List<String> command) {
        if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
            return "Audio command";
        }
        String value = command.get(0).replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    private String extensionFor(String contentType, String objectKey) {
        String normalized = value(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("mpeg") || normalized.contains("mp3")) return ".mp3";
        if (normalized.contains("wav")) return ".wav";
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        String key = value(objectKey).toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        return normalized.startsWith("audio/") ? ".wav" : ".mp4";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String tail(String value, int max) {
        String text = value(value);
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private Path newestWav(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        Path newest = null;
        long newestModified = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.wav")) {
            for (Path item : stream) {
                long modified = Files.getLastModifiedTime(item).toMillis();
                if (newest == null || modified > newestModified) {
                    newest = item;
                    newestModified = modified;
                }
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not inspect neural denoise output directory.", ex);
        }
        return newest;
    }

    private String stringControl(Map<String, Object> controls, String key, String fallback) {
        Object value = controls == null ? null : controls.get(key);
        return value == null || String.valueOf(value).isBlank() ? value(fallback) : String.valueOf(value);
    }

    private boolean booleanControl(Map<String, Object> controls, String key, boolean fallback) {
        Object value = controls == null ? null : controls.get(key);
        return booleanValue(value, fallback);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record AudioEnhancementCommand(List<String> command, String filterChain) {
    }

    private record NeuralDenoiseResult(boolean used, Path outputPath, Map<String, Object> metadata) {
    }

    public record RenderedAudio(String filename, String contentType, byte[] bytes, Map<String, Object> metadata) {
    }
}
