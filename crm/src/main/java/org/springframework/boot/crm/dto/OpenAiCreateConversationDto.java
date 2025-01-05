package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OpenAiCreateConversationDto {

    private String event_id;
    private String type;
    private String previous_item_id;
    private Item item;

    @Data
    public static class Item {
        private String id;
        private String type;
        private String status;
        private String role;
        private List<Content> content = new ArrayList<>();

        @Data
        public static class Content {
            private String type;
            private String text;
        }
    }
}
