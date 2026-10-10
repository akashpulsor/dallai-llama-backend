package com.dalai.llama.tenant.youtube.client;

/** YouTube could not answer: no API key configured, quota exhausted, or a Google outage. Mapped
 * to 503 so the UI says "try again later" rather than "you did something wrong". */
public class YouTubeUnavailableException extends RuntimeException {

    public YouTubeUnavailableException(String message) {
        super(message);
    }

    public YouTubeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
