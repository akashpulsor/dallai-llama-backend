package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class Principal  extends BusinessData implements UserDetails {

    private static final long serialVersionUID = 1L;

    private int id;

    private String mobile;
    private String username;

    private String email;

    @JsonIgnore
    private String password;

    private Collection<? extends GrantedAuthority> authorities;

    public Principal(BusinessData businessData) {
        this.authorities = businessData.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toList());
        this.setId(businessData.getBusinessId());
        this.setMobile(businessData.getMobile());
        this.setBusinessName(businessData.getBusinessName());
        this.setEmail(businessData.getEmail());
        this.setPassword(businessData.getPassword());
    }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    @Override
    public String getPassword() {
        return super.getPassword();
    }

    @Override
    public String getUsername() {
        return super.getBusinessName();
    }

    @Override
    public boolean isEnabled() {
        return super.isActive();
    }
}
