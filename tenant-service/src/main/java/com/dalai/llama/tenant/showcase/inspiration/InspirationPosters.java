package com.dalai.llama.tenant.showcase.inspiration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** A pin's picture, video file and maker from its public pin info (the data the embed widget
 * reads), so inspiration shows in mail and plays in our own player with no third-party embed.
 * Empty when the pin info doesn't answer; the item is then left out until it does. */
@Slf4j
@Component
public class InspirationPosters {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** {@code videoUrl}/{@code durationSeconds} null for a pin that is an image. */
    public record Poster(String url, int width, int height, String makerName, String videoUrl, java.math.BigDecimal durationSeconds) {
    }

    private final ObjectMapper json;
    private final String infoUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public InspirationPosters(ObjectMapper json,
                              @Value("${showcase.inspiration.pin-info-url:https://widgets.pinterest.com/v3/pidgets/pins/info/?pin_ids=}") String infoUrl) {
        this.json = json;
        this.infoUrl = infoUrl;
    }

    public Optional<Poster> fetch(String pinId) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(infoUrl + pinId)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            return poster(json.readTree(response.body()).path("data").path(0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("No picture for pin {}: {}", pinId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The 564px picture (sharp in a 600px mail), else the largest there is. */
    static Optional<Poster> poster(JsonNode pin) {
        JsonNode images = pin.path("images");
        JsonNode image = images.has("564x") ? images.path("564x") : images.path("237x");
        String url = image.path("url").asText("");
        if (!url.startsWith("https://")) return Optional.empty();
        String maker = pin.path("native_creator").path("full_name").asText(pin.path("pinner").path("full_name").asText(""));
        JsonNode video = pin.path("videos").path("video_list").path("V_720P");
        String videoUrl = video.path("url").asText("");
        return Optional.of(new Poster(url, image.path("width").asInt(0), image.path("height").asInt(0),
                maker.isBlank() ? null : maker.trim(), videoUrl.startsWith("https://") ? videoUrl : null,
                video.has("duration") ? java.math.BigDecimal.valueOf(video.path("duration").asDouble() / 1000.0)
                        .setScale(2, java.math.RoundingMode.HALF_UP) : null));
    }
}
