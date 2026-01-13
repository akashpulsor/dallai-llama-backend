package com.dalai.llama.product.service.didww.dto;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidwwTrunkRequest {

    private String name;
    private String sipConfigId;
    private boolean cliValidation;
}
