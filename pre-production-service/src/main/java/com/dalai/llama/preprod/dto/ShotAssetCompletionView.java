package com.dalai.llama.preprod.dto;

import java.util.UUID;

/** Per-shot "is every asset this shot needs actually generated" signal for the shots list UI (a
 * green check next to a fully-generated shot) -- distinct from {@link ShotAssetBatchJobView},
 * which only tracks aggregate progress for the batch run as a whole. */
public record ShotAssetCompletionView(UUID shotId, boolean complete) {
}
