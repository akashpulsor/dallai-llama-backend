package com.dalai.llama.pbx.core.util;

public class DomainNamer {

    public static String clientDns(String label, String client, String authRealm) {
        String base = baseDomain(authRealm);
        String slug = slug(client);
        return label + "." + slug + ".dalaillama." + base;
    }

    public static String slug(String s) {
        if (s == null) return "client";
        return s.toLowerCase().replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
    }

    public static String baseDomain(String realm) {
        if (realm == null) return "example.com";
        String[] parts = realm.split("\\.");
        if (parts.length < 2) return realm;
        return parts[parts.length-2] + "." + parts[parts.length-1];
    }
}
