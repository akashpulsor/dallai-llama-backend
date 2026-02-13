// DidListResponse.java
package com.dalai.llama.product.service.epsilon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DidListResponse {

    private int status;
    private String title;
    private String type;
    private String message;
    private List<DidData> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DidData {

        private Long id;

        @JsonProperty("did_number")
        private Long didNumber;

        @JsonProperty("is_paid_did")
        private Integer isPaidDid;
    }
}
