package com.dalai.llama.tenant.listener;

import com.dalai.llama.tenant.domain.event.CredentialDeliveryEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class CredentialDeliveryAuditListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCredentialDeliveryEvent(CredentialDeliveryEvent event) {
        log.info("CREDENTIAL_AUDIT: delivery={} tenant={} tenantUser={} kcUser={} {} -> {} actor={}",
                event.deliveryId(),
                event.tenantId(),
                event.tenantUserId(),
                event.keycloakUserId(),
                event.previousStatus(),
                event.newStatus(),
                event.actorSubject());
    }
}
