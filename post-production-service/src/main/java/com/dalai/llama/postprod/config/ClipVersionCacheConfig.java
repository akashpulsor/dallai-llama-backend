package com.dalai.llama.postprod.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * A shared cache in front of the reads the video page makes constantly.
 *
 * <p>The page asks for a shot's cuts on every card render and for a project's current cuts on every
 * load, and those rows change only when a creator makes or accepts one. That is the shape caching
 * exists for -- but it has to be shared, not per-JVM: two replicas with private caches disagree
 * about which cut is current the moment one of them serves an accept, and the creator sees the
 * answer flip depending on which pod they reach. Redis is already here for exactly this kind of
 * state, so the cache lives there.
 *
 * <p>Short TTL as a backstop, not as the mechanism. Correctness comes from evicting on write -- see
 * the {@code @CacheEvict} on the service's mutating methods; the TTL only bounds how long a stale
 * entry could survive a missed eviction, which is the difference between a bug and an outage.
 *
 * <p>Null values are not cached. "This shot has no cuts yet" is the state that changes the instant a
 * creator presses a button, and caching it is how a freshly made cut appears not to exist.
 */
@Configuration
@EnableCaching
public class ClipVersionCacheConfig {

    /** Every cut of one shot, keyed by shot id. */
    public static final String SHOT_CLIP_VERSIONS = "shotClipVersions";

    /** The current cut of every shot in a project, keyed by project id. */
    public static final String PROJECT_ACTIVE_CLIPS = "projectActiveClips";

    /**
     * The mapper the cache serializes with.
     *
     * <p>Static and public so a test can exercise the real thing. A test that builds its own copy of
     * these settings proves only that the copy works, which is worth nothing the moment the two
     * drift -- and drifting is how the bug below reached production in the first place.
     */
    public static ObjectMapper cacheObjectMapper() {
        //
        // EVERYTHING, not NON_FINAL. Every cached method here returns the result of .toList(), which
        // is a java.util.ImmutableCollections list -- and NON_FINAL does not consider those eligible
        // for a type id, so the list was written as a BARE array:
        //
        //     [{"@class":"...ShotClipVersion",...},{...}]
        //
        // The elements carry their class, the list does not. Reading that back as Object then takes
        // the first element for the type id and throws "expected VALUE_STRING ... that contains type
        // id", which reached the page as a 500 on every cache HIT -- so a shot's cuts loaded once,
        // then failed for the next five minutes until the TTL dropped the entry. EVERYTHING tags the
        // list itself, and it does so whatever list shape a method happens to return, which matters
        // more than the one-line diff suggests: the alternative fix is to make every cached method
        // return a mutable ArrayList and hope nobody ever writes .toList() again.
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
                .activateDefaultTyping(
                        com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
                        ObjectMapper.DefaultTyping.EVERYTHING,
                        com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
    }

    @Bean
    public RedisCacheManager clipVersionCacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${post-production.cache.clip-version-ttl-seconds:300}") long ttlSeconds) {

        // A cache-only mapper. The shared ObjectMapper is tuned for the wire, where type
        // information is deliberately absent; a cache has to round-trip back into the same class,
        // so it needs typing switched on -- and changing the shared one to get that would alter
        // every API response this service sends.
        ObjectMapper cacheMapper = cacheObjectMapper();


        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(cacheMapper)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .withCacheConfiguration(SHOT_CLIP_VERSIONS, configuration)
                .withCacheConfiguration(PROJECT_ACTIVE_CLIPS, configuration)
                .build();
    }
}
