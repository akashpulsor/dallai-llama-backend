package com.dalai.llama.tenant.leadmanagement.notify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.simplejavamail.utils.mail.dkim.Canonicalization;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import org.simplejavamail.utils.mail.dkim.DkimSigner;
import org.simplejavamail.utils.mail.dkim.SigningAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * DKIM-signs mail sent From the outreach domain (partner.dalaillama.in: creators' aliases and the
 * brands alias) with our own key, d=partner.dalaillama.in. Hostinger signs only the domains it
 * hosts, so without this the outreach domain's mail carries no signature and builds no
 * reputation of its own. The public key is the TXT record {@code <selector>._domainkey.<domain>}.
 * Mail From any other domain, or with no key configured, goes unsigned.
 */
@Slf4j
@Component
public class MailSigner {

    private final String domain;
    private final DkimSigner signer;

    public MailSigner(@Value("${mail-dkim.domain:partner.dalaillama.in}") String domain,
                      @Value("${mail-dkim.selector:dl1}") String selector,
                      @Value("${mail-dkim.private-key:}") String privateKey) {
        this.domain = domain.toLowerCase(Locale.ROOT);
        this.signer = privateKey == null || privateKey.isBlank() ? null : signer(this.domain, selector, privateKey);
        if (signer == null) log.warn("DKIM signing off: no key for {}", this.domain);
    }

    /** The message to send: signed when it is From the outreach domain, else as it is. Either way an
     * SMTPMessage, so the caller can set the envelope sender. */
    public SMTPMessage prepare(MimeMessage mime) throws MessagingException {
        if (signer == null || !domain.equals(fromDomain(mime))) return new SMTPMessage(mime);
        return new DkimMessage(mime, signer);
    }

    public boolean enabled() {
        return signer != null;
    }

    private static String fromDomain(MimeMessage mime) throws MessagingException {
        if (mime.getFrom() == null || mime.getFrom().length == 0) return "";
        String address = ((InternetAddress) mime.getFrom()[0]).getAddress();
        return address.substring(address.indexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    /** The key as PKCS#8 PEM, or the same DER bytes base64 on one line (easier in a k8s secret). */
    private static DkimSigner signer(String domain, String selector, String privateKey) {
        try {
            String base64 = privateKey.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            DkimSigner signer = new DkimSigner(domain, selector, key);
            signer.setSigningAlgorithm(SigningAlgorithm.SHA256_WITH_RSA);
            signer.setHeaderCanonicalization(Canonicalization.RELAXED);
            signer.setBodyCanonicalization(Canonicalization.RELAXED);
            signer.setLengthParam(false);
            // Hostinger's relay rewrites Date (to UTC) in transit, which would break a signature over it.
            signer.removeHeaderToSign("Date");
            // The key's DNS record is checked once by hand, not on every send.
            signer.setCheckDomainKey(false);
            return signer;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid DKIM private key for " + domain + ": " + e.getMessage(), e);
        }
    }
}
