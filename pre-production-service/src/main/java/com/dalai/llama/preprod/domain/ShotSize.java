package com.dalai.llama.preprod.domain;

/** Mirrors video-generation-service's own {@code ShotSize} enum names exactly -- this is a
 * wire-contract copy (Jackson serializes/deserializes enums by name), not a shared dependency;
 * each service owns its copy of a contract it depends on, same convention used everywhere else
 * in this system. */
public enum ShotSize {
    EWS, VWS, WS, MWS, MS, MCU, CU, ECU, INSERT, OTS
}
