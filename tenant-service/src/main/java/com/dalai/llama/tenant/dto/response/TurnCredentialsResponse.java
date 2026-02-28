package com.dalai.llama.tenant.dto.response;


import lombok.Data;

@Data
public class TurnCredentialsResponse {
    private String username;
    private String password;
    private String turnUrl;    // turn:host:3478?transport=udp
    private String turnsUrl;   // turns:host:5349?transport=tcp
    private String stunUrl;    // stun:host:3478
    private int ttl;
}