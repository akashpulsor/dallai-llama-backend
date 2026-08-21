package com.dalai.llama.postprod.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Separate from AssetPersistenceService on purpose -- that one copies a provider's already-known-
 * small result (a generated clip, a synthesized line); this handles a user-uploaded SOURCE video,
 * which can be arbitrarily large and must never be fully buffered into JVM heap as a byte[]. */
public interface SourceUploadService {

    AssetPersistenceService.PersistedAsset upload(UUID dubbingJobId, MultipartFile file);
}
