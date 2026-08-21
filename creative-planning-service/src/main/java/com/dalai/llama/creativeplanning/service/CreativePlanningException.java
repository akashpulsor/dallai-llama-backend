package com.dalai.llama.creativeplanning.service;

import org.springframework.http.HttpStatus;

public class CreativePlanningException extends RuntimeException {

    private final HttpStatus status;

    public CreativePlanningException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static CreativePlanningException notFound(String message) {
        return new CreativePlanningException(HttpStatus.NOT_FOUND, message);
    }

    public static CreativePlanningException badRequest(String message) {
        return new CreativePlanningException(HttpStatus.BAD_REQUEST, message);
    }

    public static CreativePlanningException conflict(String message) {
        return new CreativePlanningException(HttpStatus.CONFLICT, message);
    }

    public static CreativePlanningException upstream(String message) {
        return new CreativePlanningException(HttpStatus.BAD_GATEWAY, message);
    }

    /** A resource that existed but is no longer reachable -- an expired share link, distinct from
     * {@link #notFound}'s "never existed" so a client UI can say "ask for a new link" instead of
     * "invalid link". */
    public static CreativePlanningException gone(String message) {
        return new CreativePlanningException(HttpStatus.GONE, message);
    }

    public static CreativePlanningException forbidden(String message) {
        return new CreativePlanningException(HttpStatus.FORBIDDEN, message);
    }
}
