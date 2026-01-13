package com.dalai.llama.product.service.didww.dto;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidwwSipConfigRequest {

    private String name;
    private String host;
    private int port;
    private String transport; // UDP / TCP / TLS
}
