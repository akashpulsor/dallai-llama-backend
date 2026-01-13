package com.dalai.llama.product.dto.request;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchAvailableDidsRequest {

    @NotBlank
    private String country; // ISO-2 (IN, US, etc.)

    private String city;

    private String prefix;

    /**
     * GEOGRAPHIC | MOBILE | TOLL_FREE
     */
    private String type;

    @Positive
    private int limit = 10;
}
