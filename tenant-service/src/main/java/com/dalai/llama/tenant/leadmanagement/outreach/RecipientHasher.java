package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.common.token.PublicTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** The key every outreach rule uses for a recipient: sha256(pepper + lower-cased email). Kept after
 * the raw address is purged, so suppressions and cooldowns outlive the data (rule 17). */
@Slf4j
@Component
public class RecipientHasher {

    private final String pepper;

    public RecipientHasher(OutreachProperties properties) {
        if (properties.hashPepper() == null || properties.hashPepper().isBlank()) {
            log.warn("outreach.hash-pepper is not set; recipient hashes are unpeppered");
        }
        this.pepper = properties.hashPepper() == null ? "" : properties.hashPepper();
    }

    public static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String hash(String email) {
        return PublicTokens.sha256Hex(pepper + normalise(email));
    }
}
