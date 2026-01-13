package com.dalai.llama.product.service.didww.dto;


import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidwwOrderRequest {

    private List<OrderItem> items;

    @Getter
    @Setter
    @Builder
    public static class OrderItem {
        private String did;
        private int quantity;
    }
}
