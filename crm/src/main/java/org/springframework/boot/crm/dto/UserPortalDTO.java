package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class UserPortalDTO {
    private int userId;
    private int portalId;
    private String username;
    private String password;

}
