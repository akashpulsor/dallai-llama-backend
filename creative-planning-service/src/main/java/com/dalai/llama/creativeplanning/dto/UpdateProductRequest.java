package com.dalai.llama.creativeplanning.dto;

/** Partial update -- each field applied only when given (see {@code ProductProfileService#update}). */
public record UpdateProductRequest(String name, String description, String category) {
}
