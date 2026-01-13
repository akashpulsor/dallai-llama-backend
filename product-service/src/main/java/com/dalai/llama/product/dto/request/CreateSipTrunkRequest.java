package com.dalai.llama.product.dto.request;


import com.dalai.llama.product.domain.entity.enums.TransportProtocol;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSipTrunkRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String server;

    private int port = 5060;

    private TransportProtocol transport = TransportProtocol.UDP;

    /**
     * Optional (for credential-based auth)
     */
    private String authUsername;

    /**
     * Optional (stored encrypted later)
     */
    private String authPassword;
}
