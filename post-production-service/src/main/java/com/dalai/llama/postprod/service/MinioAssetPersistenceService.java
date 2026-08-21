package com.dalai.llama.postprod.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams provider results straight through to MinIO -- never buffers a full clip into a byte[]
 * first. At any real concurrency (many tenants dispatching at once), holding even a handful of
 * short clips fully in JVM heap simultaneously is a real crash risk, not a hypothetical one; this
 * was the original implementation's actual bug, not a hardening nice-to-have.
 */
@Service
public class MinioAssetPersistenceService implements AssetPersistenceService {

    /** MinIO's own minimum multipart part size; used whenever we stream without knowing the
     * total length up front (an "unknown size" putObject upload). */
    private static final long STREAM_PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient minioClient;
    private final WebClient webClient;
    private final String bucket;
    private final String prefix;

    public MinioAssetPersistenceService(
            MinioClient minioClient,
            WebClient.Builder webClientBuilder,
            @Value("${post-production.minio.bucket}") String bucket,
            @Value("${post-production.minio.output-prefix}") String outputPrefix
    ) {
        this.minioClient = minioClient;
        this.webClient = webClientBuilder.build();
        this.bucket = bucket;
        this.prefix = outputPrefix;
    }

    @Override
    public PersistedAsset persist(UUID postProductionJobId, String providerUrl) {
        if (providerUrl == null || providerUrl.isBlank()) {
            throw PostProductionException.upstream("Provider returned no output URL for job_id=" + postProductionJobId);
        }

        // ElevenLabsProvider (and any other synchronous, no-hosted-URL provider) hands back raw
        // bytes as a data: URI rather than something to GET. That base64 string already arrived
        // fully materialized in the /v1/chat JSON response body -- there is no streaming form of
        // it to preserve, unlike the HTTP-fetch path below, which is exactly why this stays a
        // bounded, short-lived special case (TTS/foley/music lines, never a full video) rather
        // than the general path.
        if (providerUrl.startsWith("data:")) {
            return persistDataUri(postProductionJobId, providerUrl);
        }
        return persistFromUrl(postProductionJobId, providerUrl);
    }

    private PersistedAsset persistFromUrl(UUID postProductionJobId, String providerUrl) {
        String contentType = "video/mp4"; // every URL-hosted result persisted so far is the final dubbed/lip-synced clip
        String objectKey = "%s/%s%s".formatted(prefix, postProductionJobId, extensionFor(contentType));

        var bodyFlux = webClient.get()
                .uri(providerUrl)
                .retrieve()
                .bodyToFlux(DataBuffer.class);

        // Bridges the reactive download to a plain blocking InputStream MinIO's synchronous
        // client can read from -- bytes flow from the network into a bounded pipe and straight
        // out to the PUT, never sitting fully in heap at once. The write side must run on its own
        // thread (subscribeOn): PipedOutputStream.write() blocks once the pipe buffer fills until
        // the reader (MinIO, on THIS thread) drains it, so running both sides on the same thread
        // would deadlock.
        AtomicReference<Throwable> downloadError = new AtomicReference<>();
        try (PipedOutputStream pipedOut = new PipedOutputStream();
             PipedInputStream pipedIn = new PipedInputStream(pipedOut, (int) STREAM_PART_SIZE)) {

            DataBufferUtils.write(bodyFlux, pipedOut)
                    .doOnError(downloadError::set)
                    .doFinally(signal -> closeQuietly(pipedOut))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(DataBufferUtils.releaseConsumer());

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(pipedIn, -1, STREAM_PART_SIZE)
                    .contentType(contentType)
                    .build());

            if (downloadError.get() != null) {
                // The pipe closing early on a download failure just looks like a (possibly
                // truncated) successful upload to MinIO's client -- this is what actually
                // surfaces the real failure instead of silently persisting a corrupt clip.
                throw downloadError.get();
            }
        } catch (Throwable ex) {
            throw PostProductionException.upstream(
                    "Could not stream provider result to MinIO for job_id=" + postProductionJobId + ": " + ex.getMessage());
        }
        return new PersistedAsset(bucket, objectKey);
    }

    private void closeQuietly(PipedOutputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Reader (MinIO) already stopped reading, or the pipe is already closed -- either
            // way there's nothing further to do here.
        }
    }

    private PersistedAsset persistDataUri(UUID postProductionJobId, String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0 || !dataUri.substring(0, comma).contains("base64")) {
            throw PostProductionException.upstream("Unsupported data URI (expected base64) for job_id=" + postProductionJobId);
        }
        String header = dataUri.substring("data:".length(), dataUri.indexOf(';'));
        String contentType = header.isBlank() ? "application/octet-stream" : header;
        byte[] bytes = java.util.Base64.getDecoder().decode(dataUri.substring(comma + 1));
        if (bytes.length == 0) {
            throw PostProductionException.upstream("Provider result was empty for job_id=" + postProductionJobId);
        }

        String objectKey = "%s/%s%s".formatted(prefix, postProductionJobId, extensionFor(contentType));
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw PostProductionException.upstream("Could not persist result to MinIO for job_id=" + postProductionJobId + ": " + ex.getMessage());
        }
        return new PersistedAsset(bucket, objectKey);
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "video/mp4" -> ".mp4";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            default -> ".bin";
        };
    }

    @Override
    public String presignedUrl(String bucket, String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PostProductionException.upstream("Could not create a signed URL for bucket=" + bucket + " objectKey=" + objectKey);
        }
    }
}
