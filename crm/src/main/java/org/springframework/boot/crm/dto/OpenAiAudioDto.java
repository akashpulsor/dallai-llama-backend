package org.springframework.boot.crm.dto;


import lombok.Data;

@Data
public class OpenAiAudioDto {
    private String type;
    private String event_id;
    private String response_id;
    private String item_id;
    private int output_index;
    private int content_index;
    private String delta;
}
