package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreatorCategoryKeywordResponse(
        UUID categoryId,
        String categoryCode,
        String categoryLabel,
        String sourceType,
        String locale,
        List<String> includeTerms,
        List<String> excludeTerms,
        BigDecimal weight
) {
}
