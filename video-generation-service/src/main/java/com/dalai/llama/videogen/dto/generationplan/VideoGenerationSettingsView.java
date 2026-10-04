package com.dalai.llama.videogen.dto.generationplan;

import java.util.List;

/** The creator's chosen source-generation settings, and what is wrong with them if anything is.
 * Errors mean the model cannot generate it; warnings are the creator's call to make. */
public record VideoGenerationSettingsView(
        Integer generationDurationSeconds,
        Integer generationFps,
        List<ValidationIssueView> errors,
        List<ValidationIssueView> warnings
) {
}
