package org.springframework.boot.crm.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.crm.dto.Principal;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.ERole;
import org.springframework.boot.crm.entity.RefreshToken;
import org.springframework.boot.crm.entity.Role;
import org.springframework.boot.crm.exceptions.TokenRefreshException;
import org.springframework.boot.crm.repository.RefreshTokenRepository;
import org.springframework.boot.crm.repository.RoleRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UserService  implements UserDetailsService {

    @Value("${dalai.llama.app.jwtRefreshExpirationMs}")
    private Long refreshTokenDurationMs;

    private final BusinessService businessService;

    private final RefreshTokenRepository refreshTokenRepository;

    private final RoleRepository roleRepository;

    public UserService(BusinessService businessService,
                       RefreshTokenRepository refreshTokenRepository,
                       RoleRepository roleRepository) {
        this.businessService = businessService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.roleRepository = roleRepository;
    }



    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        if(isValidEmail(username)){
            BusinessData businessData = this.businessService.getBusinessDataByEmail(username);
            return new Principal(businessData);
        }
        BusinessData businessData = this.businessService.getBusinessDataByMobile(username);
        return new Principal(businessData);
    }

    public  boolean isValidEmail(String email) {
        String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
        Pattern pattern = Pattern.compile(EMAIL_REGEX);
        Matcher matcher = pattern.matcher(email);
        return matcher.matches();
    }

    public BusinessData findUserById(int id) {
            return this.businessService.getBusinessDataById(id);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    public RefreshToken getRefreshToken(BusinessData businessData){
        return this.refreshTokenRepository.findByBusinessData(businessData);
    }
    public RefreshToken createRefreshToken(int businessId) {
        RefreshToken refreshToken = new RefreshToken();

        refreshToken.setBusinessData(this.businessService.getBusinessDataById(businessId));
        refreshToken.setExpiryDate(Instant.now().plusMillis(refreshTokenDurationMs));
        refreshToken.setToken(UUID.randomUUID().toString());

        refreshToken = refreshTokenRepository.save(refreshToken);
        return refreshToken;
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new TokenRefreshException(token.getToken(), "Refresh token was expired. Please make a new signin request");
        }

        return token;
    }

    @Transactional
    public int deleteByUserId(int businessId) {
        return refreshTokenRepository.deleteByBusinessData(this.businessService.getBusinessDataById(businessId));
    }


    public Optional<Role> findByName(ERole role) {
        return this.roleRepository.findByName(role);
    }

}
