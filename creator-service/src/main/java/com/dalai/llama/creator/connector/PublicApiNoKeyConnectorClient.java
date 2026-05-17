package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PublicApiNoKeyConnectorClient implements SourceConnectorClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PublicApiNoKeyConnectorClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "DalaiLlamaCreatorBot/1.0")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.PUBLIC_API_NO_KEY == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        return switch (request.connector().getCode()) {
            case "reddit_public_json" -> fetchRedditSearch(request);
            case "coingecko_public_api" -> fetchCoinGeckoTrending(request);
            case "binance_public_api" -> fetchBinanceTicker(request);
            default -> throw new IllegalArgumentException("Unsupported public no-key connector " + request.connector().getCode());
        };
    }

    private ConnectorFetchResult fetchRedditSearch(ConnectorFetchRequest request) {
        List<StructuredTrendSignal> signals = new ArrayList<>();
        List<String> terms = ConnectorClientSupport.queryTerms(request, 3);
        List<String> urls = new ArrayList<>();

        for (String term : terms) {
            URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/search.json", Map.of(
                    "q", term,
                    "sort", "hot",
                    "t", "day",
                    "limit", "25"
            ));
            urls.add(uri.toString());
            JsonNode root = getJson(uri, request.connector().getTimeoutMs());
            for (JsonNode child : root.at("/data/children")) {
                JsonNode data = child.get("data");
                String title = ConnectorClientSupport.text(data, "title");
                if (title == null || title.isBlank()) {
                    continue;
                }
                String permalink = ConnectorClientSupport.text(data, "permalink");
                String sourceUrl = permalink == null ? ConnectorClientSupport.text(data, "url") : "https://www.reddit.com" + permalink;
                BigDecimal score = ConnectorClientSupport.decimal(data, "score");
                BigDecimal comments = ConnectorClientSupport.decimal(data, "num_comments");
                BigDecimal rankScore = score.add(comments.multiply(BigDecimal.valueOf(2)));

                signals.add(new StructuredTrendSignal(
                        sourceUrl,
                        title,
                        ConnectorClientSupport.text(data, "selftext"),
                        title + " " + nullToEmpty(ConnectorClientSupport.text(data, "selftext")),
                        ConnectorClientSupport.text(data, "subreddit_name_prefixed"),
                        rankScore,
                        rankScore,
                        ConnectorClientSupport.epochSeconds(data, "created_utc"),
                        request.windowEndedAt(),
                        ConnectorClientSupport.mapOf("reddit", objectMapper.convertValue(data, Map.class), "query", term),
                        ConnectorClientSupport.mapOf("score", score, "comments", comments, "source", "reddit_public_json"),
                        ConnectorClientSupport.dedupe(request.connector().getCode(), sourceUrl, title)
                ));
            }
        }

        return result(request, terms, urls, signals);
    }

    private ConnectorFetchResult fetchCoinGeckoTrending(ConnectorFetchRequest request) {
        URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/search/trending", Map.of());
        JsonNode root = getJson(uri, request.connector().getTimeoutMs());
        List<StructuredTrendSignal> signals = new ArrayList<>();

        for (JsonNode coin : root.path("coins")) {
            JsonNode item = coin.path("item");
            String name = ConnectorClientSupport.text(item, "name");
            String symbol = ConnectorClientSupport.text(item, "symbol");
            if (name == null || symbol == null) {
                continue;
            }
            BigDecimal score = ConnectorClientSupport.decimal(item, "score");
            String title = name + " (" + symbol.toUpperCase() + ") trending on CoinGecko";
            signals.add(new StructuredTrendSignal(
                    "https://www.coingecko.com/en/coins/" + ConnectorClientSupport.text(item, "id"),
                    title,
                    "CoinGecko trending crypto asset signal.",
                    title,
                    "CoinGecko",
                    score,
                    BigDecimal.valueOf(100).subtract(score),
                    null,
                    request.windowEndedAt(),
                    ConnectorClientSupport.mapOf("item", objectMapper.convertValue(item, Map.class)),
                    ConnectorClientSupport.mapOf("symbol", symbol, "marketCapRank", ConnectorClientSupport.text(item, "market_cap_rank")),
                    ConnectorClientSupport.dedupe(request.connector().getCode(), name, symbol)
            ));
        }

        return result(request, List.of("coingecko trending"), List.of(uri.toString()), signals);
    }

    private ConnectorFetchResult fetchBinanceTicker(ConnectorFetchRequest request) {
        URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/api/v3/ticker/24hr", Map.of());
        JsonNode root = getJson(uri, request.connector().getTimeoutMs());
        List<StructuredTrendSignal> signals = new ArrayList<>();

        for (JsonNode ticker : root) {
            String symbol = ConnectorClientSupport.text(ticker, "symbol");
            if (symbol == null || !symbol.endsWith("USDT")) {
                continue;
            }
            BigDecimal volume = ConnectorClientSupport.decimal(ticker, "quoteVolume");
            BigDecimal change = ConnectorClientSupport.decimal(ticker, "priceChangePercent").abs();
            BigDecimal rankScore = volume.divide(BigDecimal.valueOf(1_000_000), 2, java.math.RoundingMode.HALF_UP).add(change);
            String title = symbol + " moved " + ConnectorClientSupport.text(ticker, "priceChangePercent") + "% in 24h";
            signals.add(new StructuredTrendSignal(
                    "https://www.binance.com/en/trade/" + symbol.replace("USDT", "_USDT"),
                    title,
                    "Binance 24hr ticker volume and movement signal.",
                    title,
                    "Binance",
                    volume,
                    rankScore,
                    null,
                    request.windowEndedAt(),
                    ConnectorClientSupport.mapOf("ticker", objectMapper.convertValue(ticker, Map.class)),
                    ConnectorClientSupport.mapOf("symbol", symbol, "quoteVolume", volume, "changeAbs", change),
                    ConnectorClientSupport.dedupe(request.connector().getCode(), symbol, ConnectorClientSupport.text(ticker, "lastPrice"))
            ));
        }

        return result(request, List.of("binance 24hr ticker"), List.of(uri.toString()), ConnectorClientSupport.topSignals(signals, 25));
    }

    private JsonNode getJson(URI uri, Integer timeoutMs) {
        try {
            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs));
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed public API request " + uri, ex);
        }
    }

    private ConnectorFetchResult result(
            ConnectorFetchRequest request,
            List<String> terms,
            List<String> urls,
            List<StructuredTrendSignal> signals
    ) {
        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("terms", terms);
        requestSnapshot.put("urls", urls);

        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("items", signals.size());

        Map<String, Object> structuredPayload = new LinkedHashMap<>();
        structuredPayload.put("connector", request.connector().getCode());
        structuredPayload.put("signals", signals.size());
        structuredPayload.put("windowEndedAt", OffsetDateTime.now().toString());

        return new ConnectorFetchResult(urls.size(), 200, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
