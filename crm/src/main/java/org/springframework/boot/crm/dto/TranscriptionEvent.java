package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.TranscriptionData;
import org.springframework.context.ApplicationEvent;

@Data
public class TranscriptionEvent extends ApplicationEvent {


    private final TranscriptionData transcriptionData;

    public TranscriptionEvent(Object source, TranscriptionData transcriptionData) {
        super(source);
        this.transcriptionData = transcriptionData;
    }
}
