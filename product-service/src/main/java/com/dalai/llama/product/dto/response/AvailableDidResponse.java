package com.dalai.llama.product.dto.response;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableDidResponse {

    private String number;
    private String country;
    private String city;
    private String type;
    private String setupFee;
    private String monthlyFee;
}

