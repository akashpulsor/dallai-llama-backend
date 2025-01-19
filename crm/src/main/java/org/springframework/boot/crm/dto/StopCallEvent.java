package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class StopCallEvent extends ApplicationEvent {
    private  int callId;
    public StopCallEvent(Object source, int callId) {
        super(source);
        this.callId = callId;
    }
}
