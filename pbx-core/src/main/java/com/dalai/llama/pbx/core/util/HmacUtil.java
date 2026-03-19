package com.dalai.llama.pbx.core.util;



import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA1 utility for CoTURN time-limited credentials.
 *
 * CoTURN uses TURN REST API (RFC 5389 long-term credential mechanism):
 *   username = "{expiry_unix_timestamp}:{identifier}"
 *   password = Base64(HMAC-SHA1(username, shared_secret))
 *
 * The shared_secret is configured in both CoTURN (static-auth-secret)
 * and PBX-Core (coturn.secret in application.yml).
 *
 * Agent UI receives these credentials via GET /api/v1/turn/credentials/{slug}
 * and passes them to the browser's RTCPeerConnection ICE configuration.
 *
 * Usage:
 *   long expiry = Instant.now().getEpochSecond() + 3600;
 *   String username = expiry + ":tenant_acme";
 *   String password = HmacUtil.hmacSha1(username, "coturn-shared-secret");
 */
public final class HmacUtil {

    private HmacUtil() {}

    /**
     * Compute HMAC-SHA1 and return as Base64 string.
     *
     * @param data   the data to sign (TURN username)
     * @param secret the shared secret (CoTURN static-auth-secret)
     * @return       Base64-encoded HMAC-SHA1 signature (the TURN password)
     */
    public static String hmacSha1(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(keySpec);
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA1 computation failed", e);
        }
    }
}