package com.dalai.llama.preprod.domain;

/** A real actor to cast (name, age, gender, face image, voice sample to clone) vs. a product the
 * shot exists to showcase (name/description, product image, no age/gender/voice) -- the same
 * {@link com.dalai.llama.preprod.domain.entity.CastProfile}/{@code CastAssignment} mechanism
 * carries both onto a shot, this just tags which kind a given profile is. */
public enum CastProfileType {
    ACTOR,
    PRODUCT
}
