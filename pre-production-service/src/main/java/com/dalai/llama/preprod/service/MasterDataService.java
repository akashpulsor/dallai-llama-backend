package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.AspectRatioOption;
import com.dalai.llama.preprod.domain.entity.GenderOption;
import com.dalai.llama.preprod.domain.entity.ShotTypeDefinition;
import com.dalai.llama.preprod.domain.entity.VideoFeatureFlagDefinition;
import com.dalai.llama.preprod.dto.AspectRatioOptionView;
import com.dalai.llama.preprod.dto.DialogueLanguageView;
import com.dalai.llama.preprod.dto.GenderOptionView;
import com.dalai.llama.preprod.dto.ShotTypeDefinitionView;
import com.dalai.llama.preprod.dto.VideoFeatureFlagDefinitionView;
import com.dalai.llama.preprod.repository.AspectRatioOptionRepository;
import com.dalai.llama.preprod.repository.GenderOptionRepository;
import com.dalai.llama.preprod.repository.ShotTypeDefinitionRepository;
import com.dalai.llama.preprod.repository.VideoFeatureFlagDefinitionRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** The 4 small reference catalogs a project-settings / video-generation UI needs to render real
 * choices instead of hardcoded frontend string literals -- shot types, video feature flags,
 * aspect ratios, dialogue languages. The first 3 are local tables; dialogue languages are a live
 * proxy of llm-gateway's own language_master (that's the actual source of truth -- which languages
 * a voice-clone/TTS model supports is a provider-capability fact, not something this service should
 * duplicate and let drift). Grouped in one service since each is a handful-of-rows "list active"
 * read; splitting into 4 near-identical services would be the premature abstraction this build's
 * own conventions warn against. */
@Service
public class MasterDataService {

    private final ShotTypeDefinitionRepository shotTypeDefinitionRepository;
    private final VideoFeatureFlagDefinitionRepository videoFeatureFlagDefinitionRepository;
    private final AspectRatioOptionRepository aspectRatioOptionRepository;
    private final GenderOptionRepository genderOptionRepository;
    private final LlmGatewayClient llmGatewayClient;

    public MasterDataService(
            ShotTypeDefinitionRepository shotTypeDefinitionRepository,
            VideoFeatureFlagDefinitionRepository videoFeatureFlagDefinitionRepository,
            AspectRatioOptionRepository aspectRatioOptionRepository,
            GenderOptionRepository genderOptionRepository,
            LlmGatewayClient llmGatewayClient
    ) {
        this.shotTypeDefinitionRepository = shotTypeDefinitionRepository;
        this.videoFeatureFlagDefinitionRepository = videoFeatureFlagDefinitionRepository;
        this.aspectRatioOptionRepository = aspectRatioOptionRepository;
        this.genderOptionRepository = genderOptionRepository;
        this.llmGatewayClient = llmGatewayClient;
    }

    @Transactional(readOnly = true)
    public List<ShotTypeDefinitionView> listShotTypes() {
        return shotTypeDefinitionRepository.findByActiveTrue().stream().map(this::toView).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<VideoFeatureFlagDefinitionView> listVideoFeatureFlags() {
        return videoFeatureFlagDefinitionRepository.findByActiveTrue().stream().map(this::toView).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AspectRatioOptionView> listAspectRatios() {
        return aspectRatioOptionRepository.findByActiveTrue().stream().map(this::toView).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<GenderOptionView> listGenders() {
        return genderOptionRepository.findByActiveTrue().stream().map(this::toView).collect(Collectors.toList());
    }

    public List<DialogueLanguageView> listDialogueLanguages() {
        return llmGatewayClient.listLanguages().stream()
                .map(l -> new DialogueLanguageView(l.languageCode(), l.displayName(), l.nativeName()))
                .collect(Collectors.toList());
    }

    private ShotTypeDefinitionView toView(ShotTypeDefinition d) {
        return new ShotTypeDefinitionView(d.getCode(), d.getLabel(), d.getDescription(), d.getRequiresVideoGeneration(), d.getRequiresMotionGraphics());
    }

    private VideoFeatureFlagDefinitionView toView(VideoFeatureFlagDefinition d) {
        return new VideoFeatureFlagDefinitionView(d.getFlagKey(), d.getLabel(), d.getDescription(), d.getDefaultEnabled());
    }

    private AspectRatioOptionView toView(AspectRatioOption o) {
        return new AspectRatioOptionView(o.getCode(), o.getLabel(), o.getOrientation());
    }

    private GenderOptionView toView(GenderOption g) {
        return new GenderOptionView(g.getCode(), g.getLabel());
    }
}
