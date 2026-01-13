package com.dalai.llama.tenant.util;


import java.text.Normalizer;

public final class SlugGenerator {

    private SlugGenerator() {}

    public static String generate(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("[^\\w\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-")
                .toLowerCase();
    }
}
