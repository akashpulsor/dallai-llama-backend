package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

/**
 * How far a brief has come: whether the client has paid, and the pre-production project made from
 * it once an idea was locked (null until then).
 */
public record RequirementProgressView(boolean funded, UUID projectId) {
}
