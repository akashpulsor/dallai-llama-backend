package com.dalai.llama.tenant.common.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Unguessable tokens for links in emails (sign-in, unsubscribe, tracking). Only a token's hash is
 * stored when the token grants something, so a database leak can't be replayed. */
public final class PublicTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicTokens() {
    }

    /** 32 url-safe characters (24 random bytes, 192 bits). */
    public static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
