package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rule 46: brands sign in with Keycloak; a verified email is their account. */
public class BrandIdentityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final BrandContactRepository contacts = mock(BrandContactRepository.class);
    private final ContactPointValidator validator = mock(ContactPointValidator.class);
    private final BrandSessionService sessions = new BrandSessionService(new BrandProperties("secret", 30, true, 30, 5, "", "", ""), CLOCK);
    private final BrandIdentity identity = new BrandIdentity(provider(decoder), sessions, contacts, validator, CLOCK);

    /** For controller tests that only exercise the cookie. */
    public static BrandIdentity cookieOnly(BrandSessionService sessions) {
        return new BrandIdentity(provider(null), sessions, mock(BrandContactRepository.class), mock(ContactPointValidator.class), CLOCK);
    }

    @Test
    void aVerifiedKeycloakEmailBecomesTheBrandAccount_createdOnFirstUse() {
        when(decoder.decode("good")).thenReturn(jwt(Map.of("email", "Asha@Hearth.example", "email_verified", true, "name", "Asha Rao")));
        when(contacts.findByEmail("asha@hearth.example")).thenReturn(Optional.empty());
        when(contacts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        UUID id = identity.require("Bearer good", null);

        assertThat(id).isNotNull();
        verify(contacts).saveAndFlush(org.mockito.ArgumentMatchers.argThat(c ->
                c.getEmail().equals("asha@hearth.example") && "Asha Rao".equals(c.getContactName()) && c.getVerifiedAt() != null));
        verify(validator).markVerifiedByEmail("asha@hearth.example", "BRAND_KEYCLOAK");
    }

    @Test
    void anExistingBrandIsAdoptedWithoutRewriting() {
        BrandContact known = BrandContact.builder().id(UUID.randomUUID()).email("asha@hearth.example").contactName("Asha")
                .source(BrandContact.Source.OPS_IMPORT).verifiedAt(java.time.OffsetDateTime.now(CLOCK)).build();
        known.setCreatedAt(java.time.OffsetDateTime.now(CLOCK));
        when(decoder.decode("good")).thenReturn(jwt(Map.of("email", "asha@hearth.example", "email_verified", true)));
        when(contacts.findByEmail("asha@hearth.example")).thenReturn(Optional.of(known));

        assertThat(identity.require("bearer good", null)).isEqualTo(known.getId());
        verify(contacts, never()).saveAndFlush(any());
    }

    @Test
    void anUnverifiedEmailStillGetsIn_withoutCountingAsVerified() {
        when(decoder.decode("fresh")).thenReturn(jwt(Map.of("email", "new@brand.example", "email_verified", false)));
        when(contacts.findByEmail("new@brand.example")).thenReturn(Optional.empty());
        when(contacts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(identity.require("Bearer fresh", null)).isNotNull();
        verify(contacts).saveAndFlush(org.mockito.ArgumentMatchers.argThat(c -> c.getVerifiedAt() == null));
        verify(validator, never()).markVerifiedByEmail(any(), any());
    }

    @Test
    void aBadOrMissingTokenIsRefused_andTheOldCookieStillWorks() {
        when(decoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));

        assertThatThrownBy(() -> identity.require("Bearer forged", null)).isInstanceOf(BrandNotSignedInException.class);
        assertThatThrownBy(() -> identity.require(null, null)).isInstanceOf(BrandNotSignedInException.class);
        UUID brand = UUID.randomUUID();
        assertThat(identity.require(null, sessions.issue(brand).token())).isEqualTo(brand);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims)).subject("kc-1").build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JwtDecoder> provider(JwtDecoder decoder) {
        ObjectProvider<JwtDecoder> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(decoder);
        return p;
    }
}
