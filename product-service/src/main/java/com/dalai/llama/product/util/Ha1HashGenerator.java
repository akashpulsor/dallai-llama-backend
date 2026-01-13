package com.dalai.llama.product.util;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Ha1HashGenerator {

    private Ha1HashGenerator() {}

    public static String generate(String username, String realm, String password) {
        try {
            String input = username + ":" + realm + ":" + password;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate HA1 hash", e);
        }
    }
}
