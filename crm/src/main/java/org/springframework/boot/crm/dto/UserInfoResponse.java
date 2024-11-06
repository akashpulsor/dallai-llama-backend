package org.springframework.boot.crm.dto;

import jakarta.annotation.security.DenyAll;
import lombok.Data;

import java.util.List;

@Data
public class UserInfoResponse {
    private int id;
    private String username;
    private String email;
    private List<String> roles;
}
