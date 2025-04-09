package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.List;

@Data
public class BrowserDataDto {

    private String type;
    private String sessionId;
    private int campaignId;
    private int businessId;
    private String  launchedUrl;
    private String  timestamp;
    private MetaData metaData;
    private String  browserRunId;
    private int portalId;
    private int llmId;
    private String  browserSessionId;

    @Data
    public static class MetaData {
        private String clickableElementsInfo;
        private String formElementsInfo;
        private String domSnapshot;
        private int totalClickableElementCount;
        private int totalFormElementCount;
        private String screenshotBase64;
    }
}
