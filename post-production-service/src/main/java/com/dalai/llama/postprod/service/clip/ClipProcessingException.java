package com.dalai.llama.postprod.service.clip;

/** Anything that stops a cut being produced or stored. Carries a message written for the creator
 * who pressed the button, not for a log reader -- these surface in the UI verbatim. */
public class ClipProcessingException extends RuntimeException {

    public ClipProcessingException(String message) {
        super(message);
    }

    public ClipProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
