package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;

public interface PromptBuilderService {

    BuiltPrompt buildPrompt(ShotContext shotContext, FeatureFlags effectiveFlags);
}
