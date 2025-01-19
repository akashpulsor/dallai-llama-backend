package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class BillingDataEvent  extends ApplicationEvent {

    private final OpenAiResponseDoneDto.Usage usage;
    private final int callLogId;

    public BillingDataEvent(Object source, OpenAiResponseDoneDto.Usage usage,int callLogId) {
        super(source);
        this.usage = usage;
        this.callLogId= callLogId;
    }
}
