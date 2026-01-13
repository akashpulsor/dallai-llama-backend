package com.dalai.llama.product.dto.response;


import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SipTrunkResponse {

    private UUID id;
    private String name;
    private String provider;
    private String server;
    private int port;
    private String transport;
    private String status;
}
