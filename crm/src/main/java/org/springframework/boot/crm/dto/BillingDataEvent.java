package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class BillingDataEvent  extends ApplicationEvent {

    private final BillingResponse billingResponse;
    private final String messageId;
    public BillingDataEvent(Object source, BillingResponse billingResponse, String messageId) {
        super(source);
        this.billingResponse=billingResponse;
        this.messageId=messageId;
    }
}
