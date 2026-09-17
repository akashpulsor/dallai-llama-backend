package com.dalai.llama.postprod.cache;

import com.dalai.llama.postprod.config.ClipVersionCacheConfig;
import com.dalai.llama.postprod.domain.ClipOrigin;
import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A cut list has to survive Redis.
 *
 * <p>Uses {@link ClipVersionCacheConfig#cacheObjectMapper()} itself rather than a copy of its
 * settings: the failure this guards against was a serializer that wrote something it could not read
 * back, and a test asserting against its own private mapper would have passed throughout.
 *
 * <p>The lists here are built with {@code toList()} and {@code Stream.toList()} on purpose. That is
 * what the cached service methods return, and it is precisely what broke -- the immutable list was
 * written as a bare array with no type id of its own, so every cache HIT threw and the page 500'd
 * while a cache MISS worked perfectly.
 */
class ClipVersionCacheRoundTripTest {

    private static ShotClipVersion cut(int versionNumber, ClipVersionStatus status) {
        return ShotClipVersion.builder()
                .versionId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .shotId(UUID.randomUUID())
                .shotRef("S1")
                .versionNumber(versionNumber)
                .origin(ClipOrigin.DUBBED)
                .status(status)
                .bucket("clips")
                .objectKey("a/b.mp4")
                .durationSeconds(new BigDecimal("4.200"))
                .hasAudio(true)
                .createdAt(OffsetDateTime.now())
                .acceptedAt(status == ClipVersionStatus.ACTIVE ? OffsetDateTime.now() : null)
                .build();
    }

    /** Exactly what the cache does: write, then read back as Object. */
    private static Object roundTrip(Object value) throws Exception {
        ObjectMapper mapper = ClipVersionCacheConfig.cacheObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(value), Object.class);
    }

    /** What {@code ShotClipVersionService.list} returns -- a stream collected with toList(). */
    @Test
    void aShotsCutsSurviveTheCache() throws Exception {
        List<ShotClipVersion> stored = Stream.of(
                        cut(3, ClipVersionStatus.ACTIVE),
                        cut(2, ClipVersionStatus.PREVIEW))
                .toList();

        Object back = roundTrip(stored);

        assertInstanceOf(List.class, back, "came back as " + back.getClass());
        assertEquals(2, ((List<?>) back).size());
    }

    /** A turned-down cut, with the fields V9 added. */
    @Test
    void aRejectedCutSurvivesTheCache() throws Exception {
        ShotClipVersion rejected = cut(2, ClipVersionStatus.PREVIEW);
        rejected.setRejected(true);
        rejected.setRejectedAt(OffsetDateTime.now());

        Object back = roundTrip(List.of(rejected));

        assertInstanceOf(List.class, back, "came back as " + back.getClass());
        assertEquals(1, ((List<?>) back).size());
    }

    /** What {@code activeForProject} returns, and it is cached under a different key. */
    @Test
    void aProjectsCurrentCutsSurviveTheCache() throws Exception {
        List<ShotClipVersion> stored = Stream.of(cut(1, ClipVersionStatus.ACTIVE)).toList();

        Object back = roundTrip(stored);

        assertInstanceOf(List.class, back, "came back as " + back.getClass());
        assertEquals(1, ((List<?>) back).size());
    }
}
