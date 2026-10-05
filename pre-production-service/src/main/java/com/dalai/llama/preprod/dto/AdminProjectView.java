package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A project as the ops page names it next to its costs. */
public record AdminProjectView(UUID id, String name, String status, OffsetDateTime createdAt) {
}
