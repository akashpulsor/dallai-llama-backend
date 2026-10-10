package com.dalai.llama.tenant.leadmanagement.audience;

/** Whether an email domain can receive mail. A port, so tests don't touch real DNS. */
public interface MailDomainResolver {

    enum MailDomain {
        /** Has MX records (or, per RFC 5321, an A/AAAA record to fall back to). */
        ACCEPTS_MAIL,
        /** The domain doesn't exist, has a null MX, or has nothing to deliver to. */
        NO_MAIL_SERVER,
        /** DNS didn't answer; try again later, don't judge the address. */
        UNKNOWN
    }

    MailDomain check(String domain);
}
