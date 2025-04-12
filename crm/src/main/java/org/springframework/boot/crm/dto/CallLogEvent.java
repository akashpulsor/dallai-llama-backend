package org.springframework.boot.crm.dto;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.context.ApplicationEvent;

public class CallLogEvent extends ApplicationEvent {

    private CallLog callLog;

    public CallLogEvent(Object source, CallLog callLog) {
        super(source);
        this.callLog = callLog;
    }

    public CallLog getCallLog() {
        return callLog;
    }

}
