package com.dalai.llama.product.domain.exception;

public class EpsilonApiException extends RuntimeException {

    public EpsilonApiException(String message) {
        super(message);
    }

    public EpsilonApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
