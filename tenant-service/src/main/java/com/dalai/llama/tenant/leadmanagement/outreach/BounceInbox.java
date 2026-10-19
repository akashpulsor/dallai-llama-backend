package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounces come back to the SMTP mailbox (the envelope sender of every outreach mail). Every half
 * hour the unread delivery reports there are read: an address that can never take mail is
 * suppressed (BOUNCED; nothing more goes to it and queued mail is given back) and its lead marked
 * invalid, which keeps the outreach domain's bounce rate, and so its reputation, down. Only
 * permanent failures of the address count -- no such mailbox or domain (5.1.x), mailbox disabled
 * (5.2.1), or Hostinger's own block on an address that kept bouncing ([RB]). A policy rejection
 * (other 5.7.x) can be about us, not the address, so it is only logged. Reports read are marked
 * seen; other mail in the box is left alone.
 */
@Slf4j
@Component
public class BounceInbox {

    private static final Pattern RECIPIENT = Pattern.compile("(?im)^Final-Recipient:\\s*rfc822;\\s*<?([^>\\s]+@[^>\\s]+)>?\\s*$");
    private static final Pattern STATUS = Pattern.compile("(?im)^Status:\\s*([245]\\.\\d{1,3}\\.\\d{1,3})");
    private static final Pattern DIAGNOSTIC = Pattern.compile("(?im)^Diagnostic-Code:\\s*(.*)$");

    private final OutreachStore store;
    private final RecipientHasher hasher;
    private final ContactPointValidator validator;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean enabled;

    public BounceInbox(OutreachStore store, RecipientHasher hasher, ContactPointValidator validator,
                       @Value("${outreach.bounces.imap-host:imap.hostinger.com}") String host,
                       @Value("${outreach.bounces.imap-port:993}") int port,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${outreach.bounces.enabled:true}") boolean enabled) {
        this.store = store;
        this.hasher = hasher;
        this.validator = validator;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${outreach.bounces.delay-ms:1800000}", initialDelayString = "${outreach.bounces.initial-delay-ms:300000}")
    public void scheduled() {
        if (!enabled || username.isBlank() || password.isBlank() || !hasher.configured()) return;
        try {
            int suppressed = read();
            if (suppressed > 0) log.info("Bounces: {} addresses suppressed", suppressed);
        } catch (Exception e) {
            log.warn("Bounce inbox not read: {}", e.getMessage());
        }
    }

    /** Reads the unread delivery reports; the number of addresses suppressed. */
    int read() throws Exception {
        Properties props = new Properties();
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "30000");
        Store imap = Session.getInstance(props).getStore("imaps");
        imap.connect(host, port, username, password);
        try {
            Folder inbox = imap.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            try {
                Message[] reports = inbox.search(new AndTerm(new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                        new OrTerm(new FromStringTerm("mailer-daemon"), new FromStringTerm("postmaster"))));
                int suppressed = 0;
                for (Message report : reports) {
                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    report.writeTo(raw);
                    for (String address : hardBounces(raw.toString(StandardCharsets.UTF_8))) {
                        store.suppress(hasher.hash(address), "BOUNCED");
                        validator.markInvalidByEmail(address, "BOUNCED");
                        suppressed++;
                    }
                    report.setFlag(Flags.Flag.SEEN, true);
                }
                return suppressed;
            } finally {
                inbox.close(false);
            }
        } finally {
            imap.close();
        }
    }

    /** The addresses a delivery report says can never take mail. */
    static List<String> hardBounces(String report) {
        List<String> addresses = new ArrayList<>();
        // One block per recipient, each starting at its Final-Recipient line.
        Matcher recipient = RECIPIENT.matcher(report);
        List<Integer> starts = new ArrayList<>();
        List<String> found = new ArrayList<>();
        while (recipient.find()) {
            starts.add(recipient.start());
            found.add(recipient.group(1).toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < found.size(); i++) {
            String block = report.substring(starts.get(i), i + 1 < found.size() ? starts.get(i + 1) : report.length());
            Matcher status = STATUS.matcher(block);
            if (!status.find()) continue;
            Matcher diagnostic = DIAGNOSTIC.matcher(block);
            String code = status.group(1);
            String why = diagnostic.find() ? diagnostic.group(1) : "";
            if (permanentForTheAddress(code, why)) addresses.add(found.get(i));
            else if (code.startsWith("5.")) log.info("Bounce kept (not about the address): {} {}", code, why);
        }
        return addresses;
    }

    private static boolean permanentForTheAddress(String status, String diagnostic) {
        return status.startsWith("5.1.") || status.equals("5.2.1") || (status.startsWith("5.") && diagnostic.contains("[RB]"));
    }
}
