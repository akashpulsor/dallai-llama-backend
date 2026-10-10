package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.BrandMeView;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.FollowedCreator;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.PreferencesRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignedIn;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Brand sign-up / sign-in by email link, the brand's own details, and who they follow. No
 * passwords: possession of the inbox is the proof. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAccountService {

    private final BrandContactRepository contactRepository;
    private final BrandSessionService sessions;
    private final PlatformMailer mailer;
    private final BrandProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Always succeeds from the caller's point of view (202), whether the email is new, known, or
     * over its hourly link limit, so the form reveals nothing about who has an account. */
    @Transactional
    public void requestSignIn(SignInRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String email = normalise(request.email());
        BrandContact contact = contactRepository.findByEmail(email).orElseGet(() -> BrandContact.builder()
                .id(UUID.randomUUID())
                .email(email)
                .source(BrandContact.Source.SIGNUP)
                .build());
        applyDetails(contact, request);
        // Flushed now: the sign-in row below is written with JDBC and references this row.
        contactRepository.saveAndFlush(contact);

        Integer recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead_brand_sign_in WHERE brand_contact_id = ? AND created_at > ?",
                Integer.class, contact.getId(), Timestamp.from(now.minusHours(1).toInstant()));
        if (recent != null && recent >= properties.signInLinksPerHour()) {
            log.warn("Sign-in link limit reached for brand {}", contact.getId());
            return;
        }
        String token = PublicTokens.newToken();
        jdbc.update("""
                INSERT INTO lead_brand_sign_in (token_hash, brand_contact_id, pending_action, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)""",
                PublicTokens.sha256Hex(token), contact.getId(), blankToNull(request.pendingAction()),
                Timestamp.from(now.plusMinutes(properties.signInLinkMinutes()).toInstant()), Timestamp.from(now.toInstant()));
        mailer.send(signInMail(contact, properties.signInPageUrl() + token));
    }

    /** A completed sign-in: the session for the cookie, and what the page is told. */
    public record SignInResult(BrandSessionService.Session session, SignedIn view) {
    }

    /** Opens a sign-in link: single use, expires after {@code signInLinkMinutes}. */
    @Transactional
    public SignInResult completeSignIn(String token) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT brand_contact_id, pending_action FROM lead_brand_sign_in
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
                FOR UPDATE""", PublicTokens.sha256Hex(token), Timestamp.from(now.toInstant()));
        if (rows.isEmpty()) throw new IllegalStateException("This sign-in link has expired or was already used. Ask for a new one.");
        UUID contactId = (UUID) rows.get(0).get("brand_contact_id");
        jdbc.update("UPDATE lead_brand_sign_in SET used_at = ? WHERE token_hash = ?",
                Timestamp.from(now.toInstant()), PublicTokens.sha256Hex(token));
        BrandContact contact = contactRepository.findById(contactId).orElseThrow();
        if (contact.getVerifiedAt() == null) {
            contact.setVerifiedAt(now);
            contactRepository.save(contact);
        }
        BrandSessionService.Session session = sessions.issue(contactId);
        return new SignInResult(session, new SignedIn(session.expiresAt(), (String) rows.get(0).get("pending_action"), me(contactId)));
    }

    public BrandMeView me(UUID contactId) {
        BrandContact c = contactRepository.findById(contactId).orElseThrow(BrandNotSignedInException::new);
        List<FollowedCreator> following = jdbc.query("""
                SELECT p.handle, p.display_name, p.avatar_url, p.headline
                FROM creator_follower f JOIN creator_public_profile p ON p.tenant_id = f.tenant_id
                WHERE f.brand_contact_id = ? AND p.status = 'ACTIVE'
                ORDER BY f.created_at DESC""",
                (rs, n) -> new FollowedCreator(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                contactId);
        return new BrandMeView(c.getEmail(), c.getContactName(), c.getCompanyName(), c.getWebsiteUrl(), c.getIndustry(),
                c.getCountryCode(), c.isAutoPicksOptIn(), c.getAutoCadenceDays(), following);
    }

    @Transactional
    public BrandMeView updatePreferences(UUID contactId, PreferencesRequest request) {
        BrandContact c = contactRepository.findById(contactId).orElseThrow(BrandNotSignedInException::new);
        c.setAutoPicksOptIn(request.autoPicksOptIn());
        c.setAutoCadenceDays(request.autoCadenceDays());
        if (request.industry() != null) c.setIndustry(request.industry());
        contactRepository.save(c);
        return me(contactId);
    }

    private static void applyDetails(BrandContact contact, SignInRequest request) {
        // Fill in what the brand typed; never wipe a known value with a blank.
        if (notBlank(request.contactName())) contact.setContactName(request.contactName().trim());
        if (notBlank(request.companyName())) contact.setCompanyName(request.companyName().trim());
        if (notBlank(request.websiteUrl())) contact.setWebsiteUrl(request.websiteUrl().trim());
        if (request.industry() != null) contact.setIndustry(request.industry());
        if (notBlank(request.countryCode())) contact.setCountryCode(request.countryCode().toUpperCase(Locale.ROOT));
        if (request.autoPicksOptIn()) contact.setAutoPicksOptIn(true);
    }

    private static PlatformMailer.Mail signInMail(BrandContact contact, String link) {
        String hello = notBlank(contact.getContactName()) ? "Hi " + contact.getContactName() + "," : "Hi,";
        String text = hello + "\n\nOpen this link to sign in to Dalai Llama (it works once, for 30 minutes):\n"
                + link + "\n\nIf you didn't ask for this, ignore this email.\n";
        String html = "<p>" + escape(hello) + "</p><p><a href=\"" + escape(link) + "\">Sign in to Dalai Llama</a></p>"
                + "<p style=\"color:#64748b\">The link works once, for 30 minutes. If you didn't ask for it, ignore this email.</p>";
        return new PlatformMailer.Mail(contact.getEmail(), "Your Dalai Llama sign-in link", text, html);
    }

    static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
