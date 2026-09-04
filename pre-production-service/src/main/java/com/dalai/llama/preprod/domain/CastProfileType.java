package com.dalai.llama.preprod.domain;

/** A real actor to cast (name, age, gender, face image, voice sample to clone) vs. a product the
 * shot exists to showcase (name/description, product image, no age/gender/voice) vs. a narrator
 * voice (name/voice sample, never on screen -- still needs a face image today, same as PRODUCT,
 * since {@code CastProfile.faceRefBucket} is required; use a placeholder/branding image) -- the
 * same {@link com.dalai.llama.preprod.domain.entity.CastProfile}/{@code CastAssignment} mechanism
 * carries all three onto a shot, this just tags which kind a given profile is. */
public enum CastProfileType {
    ACTOR,
    PRODUCT,
    NARRATOR
}
