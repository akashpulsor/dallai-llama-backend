// LoginResponse.java
package com.dalai.llama.product.service.epsilon.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private String token_type;
    private String phn_login;
    private String phn_pass;
}
