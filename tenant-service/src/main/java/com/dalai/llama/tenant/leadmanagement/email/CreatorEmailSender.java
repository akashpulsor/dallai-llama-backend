package com.dalai.llama.tenant.leadmanagement.email;

/** Transport-agnostic outbound send. No implementation ships in Phase 1 -- the concrete
 * provider (SES / SendGrid / Postmark / etc.) has not been chosen. This interface exists so
 * later phases can be written against a stable seam without touching call sites.
 *
 * <p>Deliberately NOT a {@code @Service} -- Spring does not need a bean until a real
 * implementation lands. A caller that autowires it before then will fail at startup with a
 * clear NoSuchBeanDefinition, which is the correct signal ("outbound isn't wired yet"). */
public interface CreatorEmailSender {

    EmailSendResult send(CreatorEmailMessage message);
}
