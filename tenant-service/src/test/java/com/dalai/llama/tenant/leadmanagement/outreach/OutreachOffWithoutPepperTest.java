package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Recipient;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendRequest;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Without a hash pepper nothing is sent or queued: hashes made now would stop matching once a
 * pepper is set, forgetting every unsubscribe and cooldown. */
class OutreachOffWithoutPepperTest {

    private final OutreachProperties properties = new OutreachProperties("", 100, 25, 14, 4, 7, 30, "https://api.example/outreach");
    private final OutreachStore store = mock(OutreachStore.class);
    private final CreatorEmailSender sender = mock(CreatorEmailSender.class);
    private final OutreachService service = new OutreachService(store, mock(MailAllowance.class), mock(MailableFilms.class),
            mock(OutreachComposer.class), new RecipientHasher(properties), sender, mock(CreatorPublicProfileRepository.class),
            mock(BillingServiceClient.class), properties, Clock.systemUTC());

    @Test
    void creatorSendsAreRefused() {
        assertThatThrownBy(() -> service.send(UUID.randomUUID(), new SendRequest(OutreachTemplate.SHOWCASE_WORK, "pub1", null,
                List.of(new Recipient("a@x.example", null, null))))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store, sender);
    }

    @Test
    void automaticMailIsNotQueued() {
        assertThat(service.enqueue(UUID.randomUUID(), "a@x.example", null, OutreachTemplate.NEW_FILM,
                OutreachStore.Origin.FOLLOW, UUID.randomUUID())).isFalse();
        verifyNoInteractions(store);
    }
}
