package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who the brand is on the brand endpoints (CREATOR_SHOWCASE.md rule 46): a Keycloak access token
 * (realm dalai-llama, signed in or registered on brands.dalaillama.in). No email step: a brand
 * registers and is in. The first call creates the brand account for the token's email, or adopts
 * the one an earlier sign-in or ops import made; the address counts as verified only when Keycloak
 * says so. The old session cookie is still honoured until it expires.
 */
@Slf4j
@Component
public class BrandIdentity {

    private final ObjectProvider<JwtDecoder> decoder;
    private final BrandSessionService sessions;
    private final BrandContactRepository contacts;
    private final ContactPointValidator contactPoints;
    private final Clock clock;

    public BrandIdentity(ObjectProvider<JwtDecoder> decoder, BrandSessionService sessions, BrandContactRepository contacts,
                         ContactPointValidator contactPoints, Clock clock) {
        this.decoder = decoder;
        this.sessions = sessions;
        this.contacts = contacts;
        this.contactPoints = contactPoints;
        this.clock = clock;
    }

    /** The signed-in brand, or {@link BrandNotSignedInException} (401). */
    @Transactional
    public UUID require(String authorization, String sessionCookie) {
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        if (bearer == null || bearer.isEmpty()) return sessions.require(sessionCookie);
        Jwt jwt = decode(bearer).orElseThrow(BrandNotSignedInException::new);
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) throw new BrandNotSignedInException();
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(clock);
        BrandContact contact = contacts.findByEmail(normalised).orElseGet(() -> BrandContact.builder()
                .id(UUID.randomUUID()).email(normalised).source(BrandContact.Source.SIGNUP).build());
        boolean changed = contact.getCreatedAt() == null;
        if (contact.getContactName() == null && jwt.getClaimAsString("name") != null) {
            contact.setContactName(truncate(jwt.getClaimAsString("name"), 80));
            changed = true;
        }
        if (contact.getVerifiedAt() == null && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            contact.setVerifiedAt(now);
            contactPoints.markVerifiedByEmail(normalised, "BRAND_KEYCLOAK");
            changed = true;
        }
        if (changed) contacts.saveAndFlush(contact);
        return contact.getId();
    }

    private Optional<Jwt> decode(String token) {
        JwtDecoder jwtDecoder = decoder.getIfAvailable();
        if (jwtDecoder == null) return Optional.empty();
        try {
            return Optional.of(jwtDecoder.decode(token));
        } catch (JwtException e) {
            log.debug("Brand token rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
