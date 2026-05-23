package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebRequestConnectorClient implements SourceConnectorClient {

    private static final Logger log = LoggerFactory.getLogger(WebRequestConnectorClient.class);

    private static final Pattern YOUTUBE_VIDEO_PATTERN = Pattern.compile(
            "\\\"videoId\\\":\\\"([^\\\"]+)\\\".{0,2500}?\\\"title\\\":\\s*\\{\\s*\\\"runs\\\":\\s*\\[\\s*\\{\\s*\\\"text\\\":\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WebRequestConnectorClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .defaultHeader("Accept", "application/json,text/plain,*/*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.WEB_REQUEST == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        return switch (request.connector().getCode()) {
            case "google_trends_web" -> fetchGoogleTrends(request);
            case "youtube_public_pages" -> fetchYouTubeSearchPages(request);
            default -> throw new IllegalArgumentException("Unsupported web request connector " + request.connector().getCode());
        };
    }

    private ConnectorFetchResult fetchGoogleTrends(ConnectorFetchRequest request) {
        List<String> categoryTerms = ConnectorClientSupport.queryTerms(request, 20);
        List<GoogleTrendsEndpoint> endpoints = googleTrendsEndpoints(request);
        List<Map<String, Object>> attempts = new ArrayList<>();
        int requestCount = 0;
        int lastHttpStatus = 0;

        for (GoogleTrendsEndpoint endpoint : endpoints) {
            requestCount++;
            WebTextResponse response = getTextResponse(endpoint.uri(), request.connector().getTimeoutMs());
            lastHttpStatus = response.statusCode();
            Map<String, Object> attempt = ConnectorClientSupport.mapOf(
                    "url", endpoint.uri().toString(),
                    "parser", endpoint.parserType(),
                    "status", response.statusCode()
            );
            attempts.add(attempt);

            if (response.statusCode() == 404 || response.statusCode() == 410) {
                log.warn(
                        "Google Trends endpoint unavailable status={} url={} connector={} country={} targetPlatform={} category={}",
                        response.statusCode(),
                        endpoint.uri(),
                        request.connector().getCode(),
                        request.countryCode(),
                        request.targetPlatformCode(),
                        request.category().getCode()
                );
                continue;
            }

            if (!response.successful()) {
                throw new IllegalStateException("Failed web request " + endpoint.uri() + " HTTP " + response.statusCode());
            }

            try {
                List<StructuredTrendSignal> signals = "realtime".equals(endpoint.parserType())
                        ? parseGoogleRealtimeTrends(response.body(), request, categoryTerms)
                        : parseGoogleDailyTrends(response.body(), request, categoryTerms);

                Map<String, Object> requestSnapshot = new LinkedHashMap<>();
                requestSnapshot.put("attempts", attempts);
                requestSnapshot.put("selectedUrl", endpoint.uri().toString());
                requestSnapshot.put("categoryTerms", categoryTerms);
                Map<String, Object> responseSnapshot = ConnectorClientSupport.mapOf(
                        "items", signals.size(),
                        "httpStatus", response.statusCode(),
                        "parser", endpoint.parserType()
                );
                Map<String, Object> structuredPayload = ConnectorClientSupport.mapOf(
                        "signals", signals.size(),
                        "source", "google_trends_web",
                        "parser", endpoint.parserType()
                );
                return new ConnectorFetchResult(requestCount, response.statusCode(), requestSnapshot, responseSnapshot, structuredPayload, signals);
            } catch (Exception ex) {
                attempt.put("parseError", ex.getMessage());
                log.warn(
                        "Google Trends response parse failed parser={} url={} connector={} country={} targetPlatform={} category={}",
                        endpoint.parserType(),
                        endpoint.uri(),
                        request.connector().getCode(),
                        request.countryCode(),
                        request.targetPlatformCode(),
                        request.category().getCode(),
                        ex
                );
            }
        }

        log.warn(
                "Google Trends connector completed with no usable endpoint connector={} country={} targetPlatform={} category={} attempts={}",
                request.connector().getCode(),
                request.countryCode(),
                request.targetPlatformCode(),
                request.category().getCode(),
                attempts
        );

        Map<String, Object> requestSnapshot = ConnectorClientSupport.mapOf("attempts", attempts, "categoryTerms", categoryTerms);
        Map<String, Object> responseSnapshot = ConnectorClientSupport.mapOf("items", 0, "reason", "no_usable_google_trends_endpoint");
        Map<String, Object> structuredPayload = ConnectorClientSupport.mapOf("signals", 0, "source", "google_trends_web");
        return new ConnectorFetchResult(requestCount, lastHttpStatus == 0 ? null : lastHttpStatus, requestSnapshot, responseSnapshot, structuredPayload, List.of());
    }

    private List<GoogleTrendsEndpoint> googleTrendsEndpoints(ConnectorFetchRequest request) {
        URI realtimeUri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/trends/api/realtimetrends", Map.of(
                "hl", "en-US",
                "tz", "-330",
                "cat", "all",
                "fi", "0",
                "fs", "0",
                "geo", request.countryCode(),
                "ri", "300",
                "rs", "20",
                "sort", "0"
        ));
        URI dailyUri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/trends/api/dailytrends", Map.of(
                "hl", "en-US",
                "tz", "-330",
                "geo", request.countryCode(),
                "ns", "15"
        ));
        return List.of(
                new GoogleTrendsEndpoint(realtimeUri, "realtime"),
                new GoogleTrendsEndpoint(dailyUri, "daily")
        );
    }

    private List<StructuredTrendSignal> parseGoogleRealtimeTrends(
            String body,
            ConnectorFetchRequest request,
            List<String> categoryTerms
    ) throws Exception {
        JsonNode root = objectMapper.readTree(ConnectorClientSupport.stripGoogleJsonPrefix(body));
        JsonNode stories = root.path("storySummaries").path("trendingStories");
        if (!stories.isArray()) {
            stories = root.path("default").path("trendingStories");
        }

        List<StructuredTrendSignal> signals = new ArrayList<>();
        for (JsonNode story : stories) {
            String title = googleStoryTitle(story);
            String articleSnippet = firstRealtimeArticleSnippet(story);
            String entities = joinTextArray(story.path("entityNames"));
            String signalText = title + " " + nullToEmpty(articleSnippet) + " " + entities;
            if (title == null || title.isBlank()) {
                continue;
            }
            if (!matchesCategory(signalText, categoryTerms)) {
                continue;
            }

            String sourceUrl = firstRealtimeArticleUrl(story);
            if (sourceUrl == null || sourceUrl.isBlank()) {
                sourceUrl = ConnectorClientSupport.text(story, "shareUrl");
            }
            if (sourceUrl == null || sourceUrl.isBlank()) {
                sourceUrl = "https://trends.google.com/trends/explore?geo=" + request.countryCode() + "&q=" + ConnectorClientSupport.encode(title);
            }

            String traffic = ConnectorClientSupport.text(story, "trafficBucketLowerBound");
            BigDecimal score = trafficScore(traffic);
            signals.add(new StructuredTrendSignal(
                    sourceUrl,
                    title,
                    defaultString(articleSnippet, "Realtime Google Trends story for " + title + "."),
                    signalText,
                    "Google Trends",
                    score,
                    score,
                    null,
                    request.windowEndedAt(),
                    ConnectorClientSupport.mapOf("story", objectMapper.convertValue(story, Map.class)),
                    ConnectorClientSupport.mapOf("trafficBucketLowerBound", traffic, "source", "google_trends_web", "parser", "realtime"),
                    ConnectorClientSupport.dedupe(request.connector().getCode(), request.countryCode(), title)
            ));
        }
        return signals;
    }

    private List<StructuredTrendSignal> parseGoogleDailyTrends(
            String body,
            ConnectorFetchRequest request,
            List<String> categoryTerms
    ) throws Exception {
        JsonNode root = objectMapper.readTree(ConnectorClientSupport.stripGoogleJsonPrefix(body));
        List<StructuredTrendSignal> signals = new ArrayList<>();
        for (JsonNode day : root.path("default").path("trendingSearchesDays")) {
            for (JsonNode trend : day.path("trendingSearches")) {
                String query = ConnectorClientSupport.textAt(trend, "/title/query");
                String traffic = ConnectorClientSupport.text(trend, "formattedTraffic");
                String articleSnippet = firstArticleSnippet(trend);
                if (query == null || query.isBlank()) {
                    continue;
                }
                if (!matchesCategory(query + " " + articleSnippet, categoryTerms)) {
                    continue;
                }
                BigDecimal score = trafficScore(traffic);
                signals.add(new StructuredTrendSignal(
                        "https://trends.google.com/trends/explore?geo=" + request.countryCode() + "&q=" + ConnectorClientSupport.encode(query),
                        query,
                        articleSnippet,
                        query + " " + nullToEmpty(articleSnippet),
                        "Google Trends",
                        score,
                        score,
                        null,
                        request.windowEndedAt(),
                        ConnectorClientSupport.mapOf("trend", objectMapper.convertValue(trend, Map.class)),
                        ConnectorClientSupport.mapOf("formattedTraffic", traffic, "source", "google_trends_web", "parser", "daily"),
                        ConnectorClientSupport.dedupe(request.connector().getCode(), request.countryCode(), query)
                ));
            }
        }
        return signals;
    }

    private ConnectorFetchResult fetchYouTubeSearchPages(ConnectorFetchRequest request) {
        List<String> terms = ConnectorClientSupport.queryTerms(request, 3);
        List<String> urls = new ArrayList<>();
        List<StructuredTrendSignal> signals = new ArrayList<>();

        for (String term : terms) {
            URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/results", Map.of(
                    "search_query", term + " shorts"
            ));
            urls.add(uri.toString());
            String html = getBody(uri, request.connector().getTimeoutMs());
            Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(html);
            int rank = 0;
            while (matcher.find() && rank < 20) {
                rank++;
                String videoId = matcher.group(1);
                String title = unescape(matcher.group(2));
                String sourceUrl = "https://www.youtube.com/watch?v=" + videoId;
                BigDecimal score = BigDecimal.valueOf(100L - rank);
                signals.add(new StructuredTrendSignal(
                        sourceUrl,
                        title,
                        "YouTube public search result for " + term + ".",
                        title + " " + term,
                        "YouTube",
                        score,
                        score,
                        null,
                        request.windowEndedAt(),
                        ConnectorClientSupport.mapOf("videoId", videoId, "query", term),
                        ConnectorClientSupport.mapOf("rank", rank, "source", "youtube_public_pages"),
                        ConnectorClientSupport.dedupe(request.connector().getCode(), videoId)
                ));
            }
        }

        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("terms", terms);
        requestSnapshot.put("urls", urls);
        Map<String, Object> responseSnapshot = ConnectorClientSupport.mapOf("items", signals.size());
        Map<String, Object> structuredPayload = ConnectorClientSupport.mapOf("signals", signals.size(), "source", "youtube_public_pages");
        return new ConnectorFetchResult(urls.size(), 200, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private String getBody(URI uri, Integer timeoutMs) {
        WebTextResponse response = getTextResponse(uri, timeoutMs);
        if (!response.successful()) {
            throw new IllegalStateException("Failed web request " + uri + " HTTP " + response.statusCode());
        }
        return response.body();
    }

    private WebTextResponse getTextResponse(URI uri, Integer timeoutMs) {
        try {
            WebTextResponse response = webClient.get()
                    .uri(uri)
                    .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new WebTextResponse(clientResponse.statusCode().value(), body)))
                    .block(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs));
            if (response == null) {
                throw new IllegalStateException("No response returned");
            }
            return response;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed web request " + uri, ex);
        }
    }

    private String firstArticleSnippet(JsonNode trend) {
        JsonNode article = trend.path("articles").isArray() && trend.path("articles").size() > 0
                ? trend.path("articles").get(0)
                : null;
        return ConnectorClientSupport.text(article, "snippet");
    }

    private String googleStoryTitle(JsonNode story) {
        JsonNode title = story.path("title");
        if (title.isObject()) {
            return ConnectorClientSupport.text(title, "query");
        }
        if (!title.isMissingNode() && !title.isNull()) {
            return title.asText();
        }
        return ConnectorClientSupport.text(story, "displayName");
    }

    private String firstRealtimeArticleSnippet(JsonNode story) {
        JsonNode article = firstArrayItem(story.path("articles"));
        String snippet = ConnectorClientSupport.text(article, "snippet");
        if (snippet == null || snippet.isBlank()) {
            snippet = ConnectorClientSupport.text(article, "articleTitle");
        }
        if (snippet == null || snippet.isBlank()) {
            snippet = ConnectorClientSupport.text(article, "title");
        }
        return snippet;
    }

    private String firstRealtimeArticleUrl(JsonNode story) {
        JsonNode article = firstArrayItem(story.path("articles"));
        String url = ConnectorClientSupport.text(article, "url");
        if (url == null || url.isBlank()) {
            url = ConnectorClientSupport.text(article, "articleUrl");
        }
        return url;
    }

    private JsonNode firstArrayItem(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0 ? node.get(0) : null;
    }

    private String joinTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNull()) {
                values.add(item.asText());
            }
        }
        return String.join(" ", values);
    }

    private boolean matchesCategory(String value, List<String> terms) {
        String lower = value == null ? "" : value.toLowerCase();
        return terms.stream().anyMatch(term -> lower.contains(term.toLowerCase()));
    }

    private BigDecimal trafficScore(String traffic) {
        if (traffic == null || traffic.isBlank()) {
            return BigDecimal.ONE;
        }
        String normalized = traffic.toLowerCase().replace("+", "").replace(",", "").trim();
        try {
            if (normalized.endsWith("k")) {
                return new BigDecimal(normalized.substring(0, normalized.length() - 1)).multiply(BigDecimal.valueOf(1000));
            }
            if (normalized.endsWith("m")) {
                return new BigDecimal(normalized.substring(0, normalized.length() - 1)).multiply(BigDecimal.valueOf(1_000_000));
            }
            return new BigDecimal(normalized.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ex) {
            return BigDecimal.ONE;
        }
    }

    private String unescape(String value) {
        return value == null ? "" : value.replace("\\u0026", "&").replace("\\\"", "\"");
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record GoogleTrendsEndpoint(URI uri, String parserType) {
    }

    private record WebTextResponse(int statusCode, String body) {
        private boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
