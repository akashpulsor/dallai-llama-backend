package com.dalai.llama.product.dto.response;

import com.dalai.llama.product.domain.entity.enums.DidStatus;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidResponse {

    private UUID id;
    private String number;
    private String displayNumber;
    private DidStatus status;
}
