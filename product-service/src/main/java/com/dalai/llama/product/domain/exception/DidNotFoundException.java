package com.dalai.llama.product.domain.exception;

public class DidNotFoundException extends RuntimeException {
    public DidNotFoundException(String id) {
      super("DID not found: " + id);
    }
}
