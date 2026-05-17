package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.entity.CreatorCategory;
import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import com.dalai.llama.creator.domain.entity.CreatorSourceConnector;

import java.time.OffsetDateTime;
import java.util.List;

public record ConnectorFetchRequest(
        CreatorSourceConnector connector,
        CreatorCategory category,
        List<CreatorCategoryKeyword> keywords,
        String targetPlatformCode,
        String countryCode,
        OffsetDateTime windowStartedAt,
        OffsetDateTime windowEndedAt
) {
}
