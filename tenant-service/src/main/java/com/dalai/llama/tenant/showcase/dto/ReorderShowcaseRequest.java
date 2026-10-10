package com.dalai.llama.tenant.showcase.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** The creator's items in the order they want them shown. */
public record ReorderShowcaseRequest(@NotNull List<UUID> itemIds) {
}
