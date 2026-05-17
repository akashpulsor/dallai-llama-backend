package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorCategory;
import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import com.dalai.llama.creator.dto.response.CreatorCategoryKeywordResponse;
import com.dalai.llama.creator.dto.response.CreatorCategoryResponse;
import com.dalai.llama.creator.repository.CreatorCategoryKeywordRepository;
import com.dalai.llama.creator.repository.CreatorCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CreatorCategoryService {

    private final CreatorCategoryRepository categoryRepository;
    private final CreatorCategoryKeywordRepository keywordRepository;

    public CreatorCategoryService(
            CreatorCategoryRepository categoryRepository,
            CreatorCategoryKeywordRepository keywordRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.keywordRepository = keywordRepository;
    }

    @Transactional(readOnly = true)
    public List<CreatorCategoryResponse> listVisibleCategories() {
        return categoryRepository.findByActiveTrueAndVisibleTrueOrderBySortOrderAscDisplayNameAsc()
                .stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CreatorCategoryKeywordResponse> listActiveSchedulerKeywords() {
        return keywordRepository.findActiveSchedulerKeywords()
                .stream()
                .map(this::toKeywordResponse)
                .toList();
    }

    private CreatorCategoryResponse toCategoryResponse(CreatorCategory category) {
        return new CreatorCategoryResponse(
                category.getId(),
                category.getCode(),
                category.getDisplayName(),
                category.getDisplayName(),
                category.getDescription(),
                category.getIconKey(),
                category.getSortOrder() == null ? 1000 : category.getSortOrder(),
                category.getPromptContext()
        );
    }

    private CreatorCategoryKeywordResponse toKeywordResponse(CreatorCategoryKeyword keyword) {
        CreatorCategory category = keyword.getCategory();
        return new CreatorCategoryKeywordResponse(
                category.getId(),
                category.getCode(),
                category.getDisplayName(),
                keyword.getSourceType(),
                keyword.getLocale(),
                List.copyOf(keyword.getIncludeTerms()),
                List.copyOf(keyword.getExcludeTerms()),
                keyword.getWeight()
        );
    }
}
