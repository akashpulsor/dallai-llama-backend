package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.dto.GenerateSoundLayerRequest;
import com.dalai.llama.postprod.dto.SoundLayerView;
import com.dalai.llama.postprod.dto.UpdateSoundLayerRequest;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.AudioGenerationResult;
import com.dalai.llama.postprod.service.FoleyGenerationService;
import com.dalai.llama.postprod.service.MusicGenerationService;
import com.dalai.llama.postprod.service.PostProductionException;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
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
 * anchored to a shot. Generated from a description or uploaded; stored once; moved, levelled and
 * switched on or off freely, because nothing is baked into a clip -- the film render mixes the
 * included layers ({@link FilmSoundtrack}).
 */
@Service
@RequiredArgsConstructor
public class SoundLayerService {

    /** A cue sits under the dialogue and the score; an effect is meant to be heard. */
    static final BigDecimal MUSIC_VOLUME_DB = BigDecimal.valueOf(-14);
    static final BigDecimal EFFECT_VOLUME_DB = BigDecimal.valueOf(-4);

    private final SoundLayerRepository repository;
    private final FoleyGenerationService foleyGenerationService;
    private final MusicGenerationService musicGenerationService;
    private final ShotClipVersionService clipVersionService;
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

    @Transactional
    public SoundLayerView generate(UUID tenantId, UUID projectId, UUID userId, GenerateSoundLayerRequest request) {
        requireShotInProject(tenantId, projectId, request.shotId());
        UUID layerId = UUID.randomUUID();
        String idempotencyKey = "sound-layer-" + layerId;
        AudioGenerationResult generated = request.kind() == SoundLayerKind.MUSIC
                ? musicGenerationService.generateMusic(tenantId, projectId, idempotencyKey, request.prompt(),
                        request.durationSeconds(), null)
                : foleyGenerationService.generateFoley(tenantId, projectId, idempotencyKey, null, request.prompt(), null);
        Path workDir = ffmpeg.createWorkDir("sound-" + layerId);
        try {
            Path audio = workDir.resolve("layer" + extensionOf(generated.audioUrl()));
            clipVersionService.fetch(generated.audioUrl(), audio);
            return toView(store(layerId, tenantId, projectId, userId, request.shotId(), request.kind(),
                    SoundLayerSource.GENERATED, request.prompt(), offsetOrZero(request.offsetMs()), audio));
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    @Transactional
    public SoundLayerView upload(UUID tenantId, UUID projectId, UUID userId, UUID shotId, SoundLayerKind kind,
                                 Integer offsetMs, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PostProductionException.badRequest("No file was uploaded");
        }
        requireShotInProject(tenantId, projectId, shotId);
        UUID layerId = UUID.randomUUID();
        Path workDir = ffmpeg.createWorkDir("sound-" + layerId);
        try {
            Path audio = workDir.resolve("layer" + extensionOf(file.getOriginalFilename()));
            try {
                file.transferTo(audio);
            } catch (Exception ex) {
                throw PostProductionException.badRequest("Could not read the uploaded file: " + ex.getMessage());
            }
            return toView(store(layerId, tenantId, projectId, userId, shotId, kind, SoundLayerSource.UPLOADED, null,
                    offsetOrZero(offsetMs), audio));
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
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

    /** The layers the film render mixes. */
    @Transactional(readOnly = true)
    public List<SoundLayer> includedForFilm(UUID projectId) {
        return repository.findByProjectIdAndIncludedTrue(projectId);
    }

    private SoundLayer store(UUID layerId, UUID tenantId, UUID projectId, UUID userId, UUID shotId, SoundLayerKind kind,
                             SoundLayerSource source, String prompt, int offsetMs, Path audio) {
        ClipProbe probe = ffmpeg.probe(audio);
        if (!probe.hasAudio()) {
            throw PostProductionException.badRequest("That file has no sound in it");
        }
        String extension = extensionOf(audio.getFileName().toString());
        String objectKey = "sound-layers/%s/%s%s".formatted(projectId, layerId, extension);
        objectStore.upload(objectKey, audio, contentTypeFor(extension));
        boolean music = kind == SoundLayerKind.MUSIC;
        OffsetDateTime now = OffsetDateTime.now();
        return repository.save(SoundLayer.builder()
                .layerId(layerId)
                .tenantId(tenantId)
                .projectId(projectId)
                .shotId(shotId)
                .kind(kind)
                .source(source)
                .prompt(prompt)
                .bucket(objectStore.bucket())
                .objectKey(objectKey)
                .durationSeconds(probe.durationSeconds())
                .offsetMs(offsetMs)
                .volumeDb(music ? MUSIC_VOLUME_DB : EFFECT_VOLUME_DB)
                // A cue eases in and out under the scene; an effect starts on its hit.
                .fadeInMs(music ? 500 : 0)
                .fadeOutMs(music ? 1500 : 150)
                .included(true)
                .createdBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build());
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
        return new SoundLayerView(layer.getLayerId(), layer.getShotId(), layer.getKind(), layer.getSource(),
                layer.getPrompt(), objectStore.presignedUrl(layer.getBucket(), layer.getObjectKey()),
                layer.getDurationSeconds(), layer.getOffsetMs(), layer.getVolumeDb(), layer.getFadeInMs(),
                layer.getFadeOutMs(), layer.isIncluded(), layer.getCreatedAt());
    }

    private static int offsetOrZero(Integer offsetMs) {
        return offsetMs == null ? 0 : Math.max(0, offsetMs);
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

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case ".wav" -> "audio/wav";
            case ".ogg" -> "audio/ogg";
            case ".m4a", ".aac" -> "audio/mp4";
            case ".flac" -> "audio/flac";
            case ".webm" -> "audio/webm";
            default -> "audio/mpeg";
        };
    }
}
