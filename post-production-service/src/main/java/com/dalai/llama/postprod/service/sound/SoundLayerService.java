package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.dto.GenerateSoundLayerRequest;
import com.dalai.llama.postprod.dto.SoundLayerView;
import com.dalai.llama.postprod.dto.UpdateSoundLayerRequest;
import com.dalai.llama.postprod.kafka.SoundLayerRequestedEvent;
import com.dalai.llama.postprod.kafka.SoundLayerRequestedPublisher;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.PostProductionException;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The creator's sound layers: music cues and sound effects placed on the film's timeline, each
 * anchored to a shot. Moved, levelled and switched on or off freely, because nothing is baked into
 * a clip -- the film render mixes the included layers ({@link FilmSoundtrack}).
 *
 * <p>This is the request side only. Adding a layer saves it QUEUED and publishes it; generating,
 * ffprobe and storing run on the worker ({@link SoundLayerProcessor}), one at a time like every
 * other ffmpeg job in this service. An upload is put in storage here as it arrives (a byte copy,
 * no ffmpeg) so the worker can pick it up.
 */
@Service
@RequiredArgsConstructor
public class SoundLayerService {

    /** A cue sits under the dialogue and the score; an effect is meant to be heard. */
    static final BigDecimal MUSIC_VOLUME_DB = BigDecimal.valueOf(-14);
    static final BigDecimal EFFECT_VOLUME_DB = BigDecimal.valueOf(-4);

    private final SoundLayerRepository repository;
    private final SoundLayerRequestedPublisher publisher;
    private final ClipObjectStore objectStore;
    private final FfmpegClipProcessor ffmpeg;
    private final PreProductionClient preProductionClient;

    @Transactional(readOnly = true)
    public List<SoundLayerView> list(UUID tenantId, UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .filter(layer -> tenantId.equals(layer.getTenantId()))
                .map(this::toView)
                .toList();
    }

    /** Not @Transactional on purpose: the row must be committed before the worker can read it. */
    public SoundLayerView generate(UUID tenantId, UUID projectId, UUID userId, GenerateSoundLayerRequest request) {
        requireShotInProject(tenantId, projectId, request.shotId());
        SoundLayer layer = newLayer(tenantId, projectId, userId, request.shotId(), request.kind(),
                SoundLayerSource.GENERATED, request.offsetMs());
        layer.setPrompt(request.prompt());
        layer.setRequestedSeconds(request.kind() == SoundLayerKind.MUSIC ? request.durationSeconds() : null);
        return enqueue(layer);
    }

    public SoundLayerView upload(UUID tenantId, UUID projectId, UUID userId, UUID shotId, SoundLayerKind kind,
                                 Integer offsetMs, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PostProductionException.badRequest("No file was uploaded");
        }
        requireShotInProject(tenantId, projectId, shotId);
        SoundLayer layer = newLayer(tenantId, projectId, userId, shotId, kind, SoundLayerSource.UPLOADED, offsetMs);
        String objectKey = objectKeyFor(projectId, layer.getLayerId(), file.getOriginalFilename());
        Path workDir = ffmpeg.createWorkDir("sound-upload-" + layer.getLayerId());
        try {
            Path received = workDir.resolve("upload" + extensionOf(objectKey));
            try {
                file.transferTo(received);
            } catch (Exception ex) {
                throw PostProductionException.badRequest("Could not read the uploaded file: " + ex.getMessage());
            }
            objectStore.upload(objectKey, received, contentTypeFor(objectKey));
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
        layer.setBucket(objectStore.bucket());
        layer.setObjectKey(objectKey);
        return enqueue(layer);
    }

    @Transactional
    public SoundLayerView update(UUID tenantId, UUID projectId, UUID layerId, UpdateSoundLayerRequest request) {
        SoundLayer layer = require(tenantId, projectId, layerId);
        if (request.offsetMs() != null) layer.setOffsetMs(request.offsetMs());
        if (request.volumeDb() != null) layer.setVolumeDb(request.volumeDb());
        if (request.fadeInMs() != null) layer.setFadeInMs(request.fadeInMs());
        if (request.fadeOutMs() != null) layer.setFadeOutMs(request.fadeOutMs());
        if (request.included() != null) layer.setIncluded(request.included());
        layer.setUpdatedAt(OffsetDateTime.now());
        return toView(repository.save(layer));
    }

    @Transactional
    public void delete(UUID tenantId, UUID projectId, UUID layerId) {
        repository.delete(require(tenantId, projectId, layerId));
    }

    /** The layers the film render mixes: switched on, and prepared. */
    @Transactional(readOnly = true)
    public List<SoundLayer> includedForFilm(UUID projectId) {
        return repository.findByProjectIdAndIncludedTrueAndStatus(projectId, FrameExtractionStatus.COMPLETED);
    }

    private SoundLayer newLayer(UUID tenantId, UUID projectId, UUID userId, UUID shotId, SoundLayerKind kind,
                                SoundLayerSource source, Integer offsetMs) {
        boolean music = kind == SoundLayerKind.MUSIC;
        OffsetDateTime now = OffsetDateTime.now();
        return SoundLayer.builder()
                .layerId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .shotId(shotId)
                .kind(kind)
                .source(source)
                .offsetMs(offsetMs == null ? 0 : Math.max(0, offsetMs))
                .volumeDb(music ? MUSIC_VOLUME_DB : EFFECT_VOLUME_DB)
                // A cue eases in and out under the scene; an effect starts on its hit.
                .fadeInMs(music ? 500 : 0)
                .fadeOutMs(music ? 1500 : 150)
                .included(true)
                .status(FrameExtractionStatus.QUEUED)
                .createdBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Saves the layer QUEUED and hands it to the worker; a layer that cannot be queued is marked
     * failed rather than left waiting for ever. */
    private SoundLayerView enqueue(SoundLayer layer) {
        SoundLayer saved = repository.save(layer);
        try {
            publisher.publish(new SoundLayerRequestedEvent(saved.getLayerId(), saved.getTenantId(),
                    saved.getProjectId(), saved.getCreatedBy()));
        } catch (PostProductionException ex) {
            saved.setStatus(FrameExtractionStatus.FAILED);
            saved.setError(ex.getMessage());
            repository.save(saved);
            throw ex;
        }
        return toView(saved);
    }

    private void requireShotInProject(UUID tenantId, UUID projectId, UUID shotId) {
        boolean inProject = preProductionClient.listShots(tenantId, projectId).stream()
                .anyMatch(shot -> shot.id().equals(shotId));
        if (!inProject) {
            throw PostProductionException.notFound("Shot " + shotId + " is not in this project");
        }
    }

    private SoundLayer require(UUID tenantId, UUID projectId, UUID layerId) {
        return repository.findByLayerIdAndTenantIdAndProjectId(layerId, tenantId, projectId)
                .orElseThrow(() -> PostProductionException.notFound("No sound layer " + layerId));
    }

    private SoundLayerView toView(SoundLayer layer) {
        String audioUrl = layer.getStatus() == FrameExtractionStatus.COMPLETED
                ? objectStore.presignedUrl(layer.getBucket(), layer.getObjectKey())
                : null;
        return new SoundLayerView(layer.getLayerId(), layer.getShotId(), layer.getKind(), layer.getSource(),
                layer.getPrompt(), layer.getStatus(), layer.getError(), audioUrl,
                layer.getDurationSeconds(), layer.getOffsetMs(), layer.getVolumeDb(), layer.getFadeInMs(),
                layer.getFadeOutMs(), layer.isIncluded(), layer.getCreatedAt());
    }

    static String objectKeyFor(UUID projectId, UUID layerId, String name) {
        return "sound-layers/%s/%s%s".formatted(projectId, layerId, extensionOf(name));
    }

    /** ".wav" from a URL or file name (query string ignored); ".mp3" when it has none we know. */
    static String extensionOf(String name) {
        if (name == null) return ".mp3";
        String path = name.split("\\?")[0];
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return ".mp3";
        String extension = path.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.(mp3|wav|ogg|m4a|aac|flac|webm)") ? extension : ".mp3";
    }

    static String contentTypeFor(String name) {
        return switch (extensionOf(name)) {
            case ".wav" -> "audio/wav";
            case ".ogg" -> "audio/ogg";
            case ".m4a", ".aac" -> "audio/mp4";
            case ".flac" -> "audio/flac";
            case ".webm" -> "audio/webm";
            default -> "audio/mpeg";
        };
    }
}
