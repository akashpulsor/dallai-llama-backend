package com.dalai.llama.preprod.domain;

/** Which cast-profile media an upload is for -- only changes the object-key prefix and validated
 * content-type family, not the storage mechanism (both go through the same MinIO bucket). */
public enum CastMediaKind {
    FACE,
    VOICE
}
