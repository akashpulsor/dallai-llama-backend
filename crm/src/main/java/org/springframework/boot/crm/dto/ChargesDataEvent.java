package org.springframework.boot.crm.dto;

import org.springframework.boot.crm.entity.ChargesData;
import org.springframework.context.ApplicationEvent;

public class ChargesDataEvent extends ApplicationEvent {

    private final ChargesData chargesData;

    public ChargesDataEvent(Object source, ChargesData chargesData) {
        super(source);
        this.chargesData = chargesData;
    }

    public ChargesData getChargesData() {
        return chargesData;
    }
}

