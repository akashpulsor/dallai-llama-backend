package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.youtube.publish.YouTubeApiClient.Failure;
import com.dalai.llama.tenant.youtube.publish.YouTubeApiClient.YouTubeCallException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads parts of a film from its presigned MinIO link with HTTP Range requests, so an upload can
 * resume mid-file and never holds more than the open stream (rule 35). */
@Component
public class FilmRangeReader {

    private static final Pattern TOTAL = Pattern.compile("/(\\d+)$");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The film's size, from a one-byte read (presigned GET links don't allow HEAD). */
    public long size(String url) {
        HttpResponse<InputStream> r = open(url, "bytes=0-0");
        try (InputStream ignored = r.body()) {
            if (r.statusCode() == 206) {
                Matcher m = TOTAL.matcher(r.headers().firstValue("Content-Range").orElse(""));
                if (m.find()) return Long.parseLong(m.group(1));
            }
            if (r.statusCode() == 200) return r.headers().firstValueAsLong("Content-Length").orElse(-1);
            throw new YouTubeCallException(Failure.TRANSIENT, "The film could not be read (HTTP " + r.statusCode() + ")");
        } catch (IOException e) {
            throw new YouTubeCallException(Failure.TRANSIENT, "The film could not be read: " + e.getMessage());
        }
    }

    /** A stream of bytes [from, from + length). The caller closes it. */
    public InputStream read(String url, long from, long length) {
        HttpResponse<InputStream> r = open(url, "bytes=" + from + "-" + (from + length - 1));
        if (r.statusCode() != 206 && !(r.statusCode() == 200 && from == 0)) {
            try { r.body().close(); } catch (IOException ignored) { /* nothing to read */ }
            throw new YouTubeCallException(Failure.TRANSIENT, "The film could not be read (HTTP " + r.statusCode() + ")");
        }
        return r.body();
    }

    private HttpResponse<InputStream> open(String url, String range) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("Range", range).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new YouTubeCallException(Failure.TRANSIENT, "The film could not be read: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeCallException(Failure.TRANSIENT, "Interrupted");
        }
    }
}
