package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MockSourceConnectorClient implements SourceConnectorClient {

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.MOCK == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        List<String> terms = request.keywords().stream()
                .flatMap(keyword -> keyword.getIncludeTerms().stream())
                .distinct()
                .limit(5)
                .toList();

        List<StructuredTrendSignal> signals = new ArrayList<>();
        OffsetDateTime observedAt = request.windowEndedAt();
        for (int index = 0; index < terms.size(); index++) {
            String term = terms.get(index);
            Map<String, Object> rawItem = new LinkedHashMap<>();
            rawItem.put("term", term);
            rawItem.put("category", request.category().getCode());
            rawItem.put("targetPlatform", request.targetPlatformCode());
            rawItem.put("source", request.connector().getCode());

            Map<String, Object> normalizedPayload = new LinkedHashMap<>();
            normalizedPayload.put("rank", index + 1);
            normalizedPayload.put("locale", firstLocale(request.keywords()));
            normalizedPayload.put("mock", true);

            signals.add(new StructuredTrendSignal(
                    null,
                    request.category().getDisplayName() + " trend: " + toTitleCase(term),
                    "Mock structured trend signal for " + term + ".",
                    term,
                    request.connector().getDisplayName(),
                    BigDecimal.valueOf(100 - (index * 7L)),
                    BigDecimal.valueOf(1.0d - (index * 0.05d)),
                    observedAt.minusMinutes(index),
                    observedAt,
                    rawItem,
                    normalizedPayload,
                    dedupeKey(request, term)
            ));
        }

        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("connector", request.connector().getCode());
        requestSnapshot.put("targetPlatform", request.targetPlatformCode());
        requestSnapshot.put("category", request.category().getCode());
        requestSnapshot.put("country", request.countryCode());
        requestSnapshot.put("terms", terms);

        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("items", signals.size());
        responseSnapshot.put("mock", true);

        Map<String, Object> structuredPayload = new LinkedHashMap<>();
        structuredPayload.put("signals", signals.size());
        structuredPayload.put("windowStartedAt", request.windowStartedAt().toString());
        structuredPayload.put("windowEndedAt", request.windowEndedAt().toString());

        return new ConnectorFetchResult(1, null, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private String firstLocale(List<CreatorCategoryKeyword> keywords) {
        return keywords.stream()
                .map(CreatorCategoryKeyword::getLocale)
                .findFirst()
                .orElse("en-IN");
    }

    private String toTitleCase(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String[] words = value.trim().split("\\s+");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            titled.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return String.join(" ", titled);
    }

    private String dedupeKey(ConnectorFetchRequest request, String term) {
        return String.join(":",
                request.targetPlatformCode(),
                request.category().getCode(),
                request.countryCode(),
                term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        );
    }
}
