package com.dalaillama.content.dto;


import lombok.Data;

@Data
public class PriceListResponseDto {
    private int priceId;
    private int llmId;
    private int toolId;
    private int price;
    private  int credit;
    private String currencyId;
    private String currencySymbol;
}
