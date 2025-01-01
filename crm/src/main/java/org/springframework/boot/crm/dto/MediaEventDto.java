package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class MediaEventDto extends TwilioMediaMessage{
    private String sequenceNumber;
    private MediaData media;

    @Data
    public static class MediaData {
        private String track;
        private String chunk;
        private String timestamp;
        private String payload;
    }
}
