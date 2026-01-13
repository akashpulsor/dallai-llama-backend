package com.dalai.llama.product.dto.request;


import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionDidRequest {

    /**
     * E.164 format (+919876543210)
     */
    @NotBlank
    private String number;

    /**
     * Optional.
     * If null → platform / default trunk is used
     */
    private String sipTrunkId;
}
