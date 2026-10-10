package com.dalai.llama.tenant.leadmanagement.audience;

import org.springframework.stereotype.Component;

import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/** MX/A lookups through the JDK's DNS provider (no extra dependency). Short timeouts: this runs
 * on the validation job and just before a send. */
@Component
public class DnsMailDomainResolver implements MailDomainResolver {

    @Override
    public MailDomain check(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes records = ctx.getAttributes(domain, new String[]{"MX", "A", "AAAA"});
            Attribute mx = records.get("MX");
            if (mx != null && mx.size() > 0) {
                // RFC 7505 null MX ("0 .") means "this domain takes no mail".
                return mx.size() == 1 && String.valueOf(mx.get(0)).trim().matches("0\\s+\\.?") ? MailDomain.NO_MAIL_SERVER
                        : MailDomain.ACCEPTS_MAIL;
            }
            return records.get("A") != null || records.get("AAAA") != null ? MailDomain.ACCEPTS_MAIL : MailDomain.NO_MAIL_SERVER;
        } catch (NameNotFoundException e) {
            return MailDomain.NO_MAIL_SERVER;
        } catch (NamingException e) {
            return MailDomain.UNKNOWN;
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (NamingException ignored) { /* nothing to release */ }
            }
        }
    }
}
