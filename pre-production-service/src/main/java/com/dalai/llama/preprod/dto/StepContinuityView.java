package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.service.continuity.ContinuityResolution.ResolvedValue;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.VisualOverride;
import com.dalai.llama.preprod.service.continuity.VisualField;

import java.util.List;
import java.util.Map;

/** What a step shot will do, for the UI's "Continuity changes" panel: every resolved field with its
 * provenance, the decisions worth showing (with reasons and whether the user can override them),
 * warnings, what is kept from the earlier shot, what this shot changes, and the exact final prompt. */
public record StepContinuityView(Map<VisualField, ResolvedValue> resolvedVisualState,
                                 List<VisualOverride> overrides,
                                 List<String> warnings,
                                 List<VisualField> preserved,
                                 List<String> changesForThisShot,
                                 String finalPrompt) {
}
