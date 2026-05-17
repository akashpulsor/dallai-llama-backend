package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorPlatform;
import com.dalai.llama.creator.dto.response.CreatorPlatformResponse;
import com.dalai.llama.creator.repository.CreatorPlatformRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CreatorPlatformService {

    private final CreatorPlatformRepository platformRepository;

    public CreatorPlatformService(CreatorPlatformRepository platformRepository) {
        this.platformRepository = platformRepository;
    }

    @Transactional(readOnly = true)
    public List<CreatorPlatformResponse> listVisibleTargetPlatforms() {
        return platformRepository.findByActiveTrueAndVisibleTrueAndTargetPlatformTrueOrderBySortOrderAscDisplayNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private CreatorPlatformResponse toResponse(CreatorPlatform platform) {
        return new CreatorPlatformResponse(
                platform.getId(),
                platform.getCode(),
                platform.getDisplayName(),
                platform.getDisplayName(),
                platform.getDescription(),
                platform.getIconKey(),
                platform.getSortOrder() == null ? 1000 : platform.getSortOrder(),
                platform.isShortForm(),
                platform.getPromptContext()
        );
    }
}
