package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.ExportBundle;

import java.util.UUID;

public interface ExportBundleService {

    /** Doc §12: prompt.txt, prompt_original.txt, negative_prompt.txt, metadata.json, references/*.
     * v1 builds synchronously (small per-shot bundles) rather than the doc's async job -- a
     * documented simplification, not a correctness gap. */
    ExportBundle requestExport(UUID tenantId, UUID promptId);

    ExportBundle getStatus(UUID tenantId, UUID bundleId);
}
