package com.dalai.llama.pbx.core.util;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * SIP Digest Authentication utilities.
 *
 * Kamailio auth_db module uses HA1/HA1B for digest authentication:
 *   HA1  = MD5(username:realm:password)
 *   HA1B = MD5(username@realm:realm:password)
 *
 * When AgentService creates an agent, it computes HA1/HA1B and stores them
 * in the subscriber table. Kamailio reads these directly from DB during
 * SIP REGISTER/INVITE authentication — the plaintext password is NEVER stored.
 *
 * realm = SIP domain (e.g., "tenant-acme.dalaillama.in")
 *
 * Usage:
 *   String ha1  = SipDigestUtil.ha1("agent1", "tenant-acme.dalaillama.in", "secretpass");
 *   String ha1b = SipDigestUtil.ha1b("agent1", "tenant-acme.dalaillama.in", "secretpass");
 */
public final class SipDigestUtil {

    private SipDigestUtil() {}

    /**
     * HA1 = MD5(username:realm:password)
     * Standard SIP digest auth — used when client sends Authorization without @domain.
     */
    public static String ha1(String username, String realm, String password) {
        return md5(username + ":" + realm + ":" + password);
    }

    /**
     * HA1B = MD5(username@realm:realm:password)
     * Used when SIP client sends auth with user@domain format.
     */
    public static String ha1b(String username, String realm, String password) {
        return md5(username + "@" + realm + ":" + realm + ":" + password);
    }

    /**
     * MD5 hex digest — lowercase 32-char hex string.
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("MD5 computation failed", e);
        }
    }
}