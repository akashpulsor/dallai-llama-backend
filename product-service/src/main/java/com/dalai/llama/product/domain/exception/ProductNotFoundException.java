package com.dalai.llama.product.domain.exception;


public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String code) {
        super("Product not found: " + code);
    }
}
