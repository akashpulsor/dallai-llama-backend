package com.dalai.llama.product.service.didww.dto;


import lombok.Data;
import java.util.List;

@Data
public class DidwwAvailableDidResponse {

    private List<DidInfo> data;

    @Data
    public static class DidInfo {
        private String number;
        private String country;
        private String city;
        private String type; // GEOGRAPHIC / MOBILE / TOLL_FREE
        private String prefix;
        private String setupFee;
        private String monthlyFee;
    }
}
