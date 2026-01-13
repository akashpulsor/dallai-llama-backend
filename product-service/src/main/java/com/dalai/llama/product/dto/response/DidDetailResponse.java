package com.dalai.llama.product.dto.response;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidDetailResponse {

    private DidResponse did;
    private SipTrunkResponse sipTrunk;
    private SipEndpointResponse sipEndpoint;
}
