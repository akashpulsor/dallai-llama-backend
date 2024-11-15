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
public class Principal   implements UserDetails {
    private static final long serialVersionUID = 1L;

    private int id;

    private String companyName;

    private String email;

    private String phone;

    @JsonIgnore
    private String password;

    private boolean accountNonExpired;
    private boolean accountNonLocked;
    private boolean credentialsNonExpired;
    private boolean enabled;
    private Collection<? extends GrantedAuthority> authorities;

    public Principal(BusinessData businessData) {
        this.id = businessData.getBusinessId();
        this.companyName = businessData.getBusinessName();
        this.email = businessData.getEmail();
        this.phone = businessData.getMobile();
        this.password = businessData.getPassword();
        this.accountNonExpired = businessData.isAccountNonExpired();
        this.accountNonLocked = businessData.isAccountNonLocked();
        this.credentialsNonExpired = businessData.isCredentialsNonExpired();
        this.enabled = businessData.isActive();
        this.authorities = businessData.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toList());
    }

    public static Principal build(BusinessData businessData) {
        Principal principal =  new Principal(businessData);
        return principal;
    }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return this.accountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return this.accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return this.credentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }
}
