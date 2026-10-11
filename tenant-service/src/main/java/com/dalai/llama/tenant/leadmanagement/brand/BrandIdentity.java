package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who the brand is on the brand endpoints (CREATOR_SHOWCASE.md rule 46): a Keycloak access token
 * (realm dalai-llama, signed in or registered on brands.dalaillama.in) whose email Keycloak has
 * verified. The first call creates the brand account for that email, or adopts the one an earlier
 * email-link sign-in or ops import made. The old session cookie is still honoured until it expires.
 */
@Slf4j
@Component
public class BrandIdentity {

    /** One confirmation email per brand user per this long, however often they retry. */
    static final Duration VERIFY_EMAIL_EVERY = Duration.ofMinutes(10);
    static final String CHECK_INBOX = "Confirm your email first: we've sent you a link. Open it, then try again.";

    private final ObjectProvider<JwtDecoder> decoder;
    private final BrandSessionService sessions;
    private final BrandContactRepository contacts;
    private final ContactPointValidator contactPoints;
    private final Clock clock;
    private final ObjectProvider<Keycloak> keycloak;
    private final String realm;
    private final String clientId;
    private final String afterVerifyUrl;
    private final Map<String, Instant> verifyEmailSent = new ConcurrentHashMap<>();

    public BrandIdentity(ObjectProvider<JwtDecoder> decoder, BrandSessionService sessions, BrandContactRepository contacts,
                         ContactPointValidator contactPoints, Clock clock, ObjectProvider<Keycloak> keycloak,
                         @Value("${keycloak.platform-realm:dalai-llama}") String realm,
                         @Value("${brands.keycloak-client-id:platform-ui}") String clientId,
                         @Value("${brands.after-verify-url:https://brands.dalaillama.in/brands/home}") String afterVerifyUrl) {
        this.decoder = decoder;
        this.sessions = sessions;
        this.contacts = contacts;
        this.contactPoints = contactPoints;
        this.clock = clock;
        this.keycloak = keycloak;
        this.realm = realm;
        this.clientId = clientId;
        this.afterVerifyUrl = afterVerifyUrl;
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
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            sendVerifyEmail(jwt.getSubject());
            throw new IllegalStateException(CHECK_INBOX);
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(clock);
        BrandContact contact = contacts.findByEmail(normalised).orElseGet(() -> BrandContact.builder()
                .id(UUID.randomUUID()).email(normalised).source(BrandContact.Source.SIGNUP).build());
        boolean changed = contact.getCreatedAt() == null;
        if (contact.getContactName() == null && jwt.getClaimAsString("name") != null) {
            contact.setContactName(truncate(jwt.getClaimAsString("name"), 80));
            changed = true;
        }
        if (contact.getVerifiedAt() == null) {
            contact.setVerifiedAt(now);
            contactPoints.markVerifiedByEmail(normalised, "BRAND_KEYCLOAK");
            changed = true;
        }
        if (changed) contacts.saveAndFlush(contact);
        return contact.getId();
    }

    /** Keycloak emails the confirmation link (the realm's SMTP); brands only, so creator sign-up is unchanged. */
    private void sendVerifyEmail(String userId) {
        if (userId == null) return;
        Instant now = clock.instant();
        Instant last = verifyEmailSent.get(userId);
        if (last != null && last.plus(VERIFY_EMAIL_EVERY).isAfter(now)) return;
        Keycloak admin = keycloak.getIfAvailable();
        if (admin == null) return;
        try {
            admin.realm(realm).users().get(userId).executeActionsEmail(clientId, afterVerifyUrl, java.util.List.of("VERIFY_EMAIL"));
            verifyEmailSent.put(userId, now);
        } catch (RuntimeException e) {
            log.warn("Could not send the brand verification email to Keycloak user {}: {}", userId, e.getMessage());
        }
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
