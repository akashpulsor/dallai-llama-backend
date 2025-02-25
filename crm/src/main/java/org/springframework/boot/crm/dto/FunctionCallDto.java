package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.IOException;
import java.util.Map;

@Data
public class FunctionCallDto {

    @JsonProperty("type")
    private String type;

    @JsonProperty("event_id")
    private String eventId;
    @JsonProperty("response_id")
    private String responseId;

    @JsonProperty("item_id")
    private String itemId;

    @JsonProperty("output_index")
    private int outputIndex;

    @JsonProperty("call_id")
    private String callId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("arguments")
    private String arguments;


    public Map<String, String> getParsedArguments() throws IOException {
        if (arguments == null) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(arguments, new TypeReference<Map<String, String>>() {});
    }

    /*
    * {
  "type" : "response.function_call_arguments.done",
  "event_id" : "event_B4ujqxuVSg5K9wX23HPGG",
  "response_id" : "resp_B4ujq8hilVmv4KNjDnbbg",
  "item_id" : "item_B4ujqxLK7dct4KdZFslqU",
  "output_index" : 0,
  "call_id" : "call_Ae8psde4tYTsV28c",
  "name" : "disconnect_call",
  "arguments" : "{\"streamId\":\"MZce2e1cad72af96f7874301e3e83c3eb1\"}"
}
    *
    * */

}
