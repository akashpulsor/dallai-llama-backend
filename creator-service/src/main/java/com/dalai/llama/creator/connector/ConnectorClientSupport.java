package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ConnectorClientSupport {

    private ConnectorClientSupport() {
    }

    static List<String> queryTerms(ConnectorFetchRequest request, int limit) {
        List<String> terms = request.keywords().stream()
                .flatMap(keyword -> keyword.getIncludeTerms().stream())
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();

        if (!terms.isEmpty()) {
            return terms;
        }

        return List.of(request.category().getDisplayName());
    }

    static String firstLocale(List<CreatorCategoryKeyword> keywords) {
        return keywords.stream()
                .map(CreatorCategoryKeyword::getLocale)
                .filter(locale -> locale != null && !locale.isBlank())
                .findFirst()
                .orElse("en-IN");
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String textAt(JsonNode node, String pointer) {
        JsonNode value = node == null ? null : node.at(pointer);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return BigDecimal.ZERO;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText().replace(",", ""));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    static OffsetDateTime epochSeconds(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(value.asLong()), ZoneOffset.UTC);
    }

    static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    static String dedupe(String connectorCode, String... values) {
        List<String> pieces = new ArrayList<>();
        pieces.add(connectorCode);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                pieces.add(value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            }
        }
        return String.join(":", pieces);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static URI uri(String baseUrl, String path, Map<String, String> query) {
        StringBuilder builder = new StringBuilder(baseUrl == null ? "" : baseUrl);
        if (path != null && !path.isBlank()) {
            if (!path.startsWith("/")) {
                builder.append('/');
            }
            builder.append(path);
        }
        if (query != null && !query.isEmpty()) {
            builder.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return URI.create(builder.toString());
    }

    static String stripGoogleJsonPrefix(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith(")]}'")) {
            int firstNewline = trimmed.indexOf('\n');
            return firstNewline >= 0 ? trimmed.substring(firstNewline + 1).trim() : trimmed.substring(4).trim();
        }
        return trimmed;
    }

    static List<StructuredTrendSignal> topSignals(List<StructuredTrendSignal> signals, int max) {
        return signals.stream()
                .sorted(Comparator.comparing(StructuredTrendSignal::rankScore).reversed())
                .limit(max)
                .toList();
    }
}
