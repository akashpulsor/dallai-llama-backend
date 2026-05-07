package com.dalai.llama.product.domain.entity.enums;

public enum SipProvider {
    /** DIDWW — global DID provider, future automated integration. */
    DIDWW,

    /** Epsilon Telecom (epsilontel.com) — primary India carrier, manual setup today. */
    EPSILON,

    /** Tata Communications. */
    TATA,

    /** Airtel Business. */
    AIRTEL,

    /** Generic catch-all for tenant BYO carriers without a dedicated enum value. */
    CUSTOM
}