package org.springframework.boot.crm.exceptions;

public class CampaignNotFoundException  extends UserNotFoundException {
    public CampaignNotFoundException(String message) {
        super(message);
    }
}
