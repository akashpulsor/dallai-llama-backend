package com.dalai.llama.product.dto.response;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SipEndpointResponse {

    private String username;
    private String domain;
    private String realm;
    private boolean active;
    private boolean registeredInKamailio;
}
