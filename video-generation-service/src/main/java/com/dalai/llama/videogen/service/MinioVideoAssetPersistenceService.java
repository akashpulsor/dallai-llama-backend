package com.dalai.llama.videogen.service;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams the provider's generated clip straight through to MinIO -- never buffers the full clip
 * into a byte[] first. The original implementation did buffer fully, which is a real crash risk
 * at any real concurrency (many tenants generating simultaneously, each holding a clip fully in
 * heap at once), not just a hardening concern for hypothetically large files.
 */
@Slf4j
@Service
public class MinioVideoAssetPersistenceService implements VideoAssetPersistenceService {

    /** MinIO's own minimum multipart part size; used for an "unknown total size" streaming
     * upload, since we don't know the clip's length until the download finishes. */
    private static final long STREAM_PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final WebClient webClient;
    private final String bucket;
    private final String prefix;

    public MinioVideoAssetPersistenceService(
            MinioClient minioClient,
            @org.springframework.beans.factory.annotation.Qualifier("publicMinioClient") MinioClient publicMinioClient,
            WebClient.Builder webClientBuilder,
            @Value("${video-gen.minio.bucket}") String bucket,
            @Value("${video-gen.minio.export-prefix}") String exportPrefix
    ) {
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.webClient = webClientBuilder.build();
        this.bucket = bucket;
        // Sibling prefix to exports, not reusing it -- generated clips and export bundles are
        // different lifecycles (clips are the durable source, exports are derived/expiring, §12).
        this.prefix = exportPrefix.replaceAll("-exports$", "") + "-clips";
    }

    @Override
    public PersistedAsset persist(UUID jobId, String providerUrl) {
        if (providerUrl == null || providerUrl.isBlank()) {
            throw VideoGenException.upstream("Provider returned no output URL for job_id=" + jobId);
        }

        String objectKey = "%s/%s.mp4".formatted(prefix, jobId);
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
                    .contentType("video/mp4")
                    .build());

            if (downloadError.get() != null) {
                // The pipe closing early on a download failure just looks like a (possibly
                // truncated) successful upload to MinIO's client -- this is what actually
                // surfaces the real failure instead of silently persisting a corrupt clip.
                throw downloadError.get();
            }
        } catch (Throwable ex) {
            throw VideoGenException.upstream("Could not stream generated clip to MinIO for job_id=" + jobId + ": " + ex.getMessage());
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

    @Override
    public void downloadTo(String bucket, String objectKey, java.nio.file.Path destination) {
        try (java.io.InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            java.nio.file.Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not download bucket=" + bucket + " objectKey=" + objectKey + " to " + destination + ": " + ex.getMessage());
        }
    }

    @Override
    public PersistedAsset uploadFile(String bucket, String objectKey, java.nio.file.Path source) {
        try {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .filename(source.toString())
                    .contentType("video/mp4")
                    .build());
            return new PersistedAsset(bucket, objectKey);
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not upload " + source + " to bucket=" + bucket + " objectKey=" + objectKey + ": " + ex.getMessage());
        }
    }

    @Override
    public String presignedUrl(String bucket, String objectKey) {
        try {
            // Signed against the PUBLIC endpoint -- these URLs are handed to fal.ai as
            // reference/source URLs and to the browser as <video src="...">, both of which live
            // outside the cluster. The internal minioClient's URLs would 502 there. See
            // MinioConfig.publicMinioClient for why the endpoint can't be swapped after signing.
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not create a signed URL for bucket=" + bucket + " objectKey=" + objectKey);
        }
    }
}
