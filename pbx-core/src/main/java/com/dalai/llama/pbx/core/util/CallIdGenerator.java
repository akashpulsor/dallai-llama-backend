package com.dalai.llama.pbx.core.util;

import java.util.UUID;

public class CallIdGenerator {

    public static String generate(String tenantId) {
        return tenantId + "-" + UUID.randomUUID();
    }
}

