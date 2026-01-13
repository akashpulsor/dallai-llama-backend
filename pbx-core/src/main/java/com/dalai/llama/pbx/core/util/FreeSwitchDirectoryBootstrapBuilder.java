package com.dalai.llama.pbx.core.util;

import org.springframework.stereotype.Component;

/**
 * Produces an initial (empty) directory config for FreeSWITCH.
 * Directory will be updated later when agents are added.
 */
@Component
public class FreeSwitchDirectoryBootstrapBuilder {

    public String build() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<include>\n" +
                "  <!-- no users registered yet; directory will be populated by PBX-Core -->\n" +
                "</include>\n";
    }
}
