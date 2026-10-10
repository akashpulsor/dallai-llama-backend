package com.dalai.llama.tenant.showcase.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Short, unguessable ids for public URLs (10 base-62 characters, about 59 bits). */
@Component
public class PublicIdGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String next() {
        StringBuilder id = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return id.toString();
    }
}
