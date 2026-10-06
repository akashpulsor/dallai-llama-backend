package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.AudioGenerationResult;
import com.dalai.llama.postprod.service.FoleyGenerationService;
import com.dalai.llama.postprod.service.MusicGenerationService;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker side of a sound layer (SoundLayerRequestedConsumer): makes the audio exist and checks
 * it. A generated layer is generated here -- the idempotency key is the layer id, so a redelivery
 * is not paid for twice -- and stored; an uploaded one is already stored and is only probed. Either
 * way the probe decides: no sound, no layer. Nothing here throws back to Kafka; a failure is
 * written on the row, where the page shows it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoundLayerProcessor {

    private final SoundLayerRepository repository;
    private final FoleyGenerationService foleyGenerationService;
    private final MusicGenerationService musicGenerationService;
    private final ShotClipVersionService clipVersionService;
    private final ClipObjectStore objectStore;
    private final FfmpegClipProcessor ffmpeg;

    public void process(UUID layerId) {
        Optional<SoundLayer> found = repository.findById(layerId);
        if (found.isEmpty() || found.get().getStatus().isFinished()) {
            log.info("Skipping sound layer layerId={} -- unknown or already finished", layerId);
            return;
        }
        SoundLayer layer = found.get();
        layer.setStatus(FrameExtractionStatus.PROCESSING);
        repository.save(layer);

        Path workDir = ffmpeg.createWorkDir("sound-" + layerId);
        try {
            Path audio = layer.getSource() == SoundLayerSource.GENERATED ? generate(layer, workDir) : download(layer, workDir);
            ClipProbe probe = ffmpeg.probe(audio);
            if (!probe.hasAudio()) {
                throw new IllegalStateException("That file has no sound in it");
            }
            if (layer.getObjectKey() == null) {
                String key = SoundLayerService.objectKeyFor(layer.getProjectId(), layerId, audio.getFileName().toString());
                objectStore.upload(key, audio, SoundLayerService.contentTypeFor(key));
                layer.setBucket(objectStore.bucket());
                layer.setObjectKey(key);
            }
            layer.setDurationSeconds(probe.durationSeconds());
            finish(layer, FrameExtractionStatus.COMPLETED, null);
            log.info("Sound layer ready layerId={} shotId={} kind={} {}s", layerId, layer.getShotId(), layer.getKind(),
                    probe.durationSeconds());
        } catch (RuntimeException ex) {
            log.warn("Sound layer failed layerId={} shotId={}: {}", layerId, layer.getShotId(), ex.getMessage());
            finish(layer, FrameExtractionStatus.FAILED, ex.getMessage());
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    private Path generate(SoundLayer layer, Path workDir) {
        String idempotencyKey = "sound-layer-" + layer.getLayerId();
        AudioGenerationResult generated = layer.getKind() == SoundLayerKind.MUSIC
                ? musicGenerationService.generateMusic(layer.getTenantId(), layer.getProjectId(), idempotencyKey,
                        layer.getPrompt(), layer.getRequestedSeconds(), null)
                : foleyGenerationService.generateFoley(layer.getTenantId(), layer.getProjectId(), idempotencyKey,
                        null, layer.getPrompt(), null);
        Path audio = workDir.resolve("layer" + SoundLayerService.extensionOf(generated.audioUrl()));
        clipVersionService.fetch(generated.audioUrl(), audio);
        return audio;
    }

    private Path download(SoundLayer layer, Path workDir) {
        Path audio = workDir.resolve("layer" + SoundLayerService.extensionOf(layer.getObjectKey()));
        objectStore.download(layer.getBucket(), layer.getObjectKey(), audio);
        return audio;
    }

    private void finish(SoundLayer layer, FrameExtractionStatus status, String error) {
        layer.setStatus(status);
        layer.setError(error);
        layer.setUpdatedAt(OffsetDateTime.now());
        repository.save(layer);
    }
}
