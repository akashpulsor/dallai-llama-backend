package org.springframework.boot.crm.service;


import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.ERole;
import org.springframework.boot.crm.entity.RefreshToken;
import org.springframework.boot.crm.entity.Role;
import org.springframework.boot.crm.exceptions.EmailExistsException;
import org.springframework.boot.crm.exceptions.PhoneNumberExistsException;
import org.springframework.boot.crm.exceptions.TokenRefreshException;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class UserManagerImpl implements UserManager {

    private final BusinessManager businessManager;

    private final UserService userService;

    private final JwtService jwtService;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager authenticationManager;



    public UserManagerImpl(BusinessManager businessManager,UserService userService,
                           JwtService jwtService,
                           AuthenticationManager authenticationManager,
                           PasswordEncoder passwordEncoder){
        this.userService = userService;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.businessManager = businessManager;
    }
    @Override
    public UserDto createUser(BusinessData businessData) {
        return modelToDto(this.businessManager.addBusiness(businessData));
    }

    @Override
    public LoginResponseDto login(LoginRequestDto loginRequestDto) {
        Authentication authentication = authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(loginRequestDto.getUserName(),
                        loginRequestDto.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        Principal userDetails = (Principal) authentication.getPrincipal();
        String jwtToken  = this.jwtService.generateJwtToken(userDetails);
        List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());
        BusinessData businessData = this.userService.findUserById(userDetails.getId());
        RefreshToken refreshToken = this.userService.getRefreshToken(businessData);
        LoginResponseDto loginResponseDto = new LoginResponseDto();
        loginResponseDto.setAccessToken(jwtToken);
        if(refreshToken!=null){
            this.userService.deleteByUserId(userDetails.getId());
        }

        refreshToken = this.userService.createRefreshToken(userDetails.getId());
        loginResponseDto.setRefreshToken(refreshToken.getToken());
        loginResponseDto.setRefreshToken(refreshToken.getToken());

        UserInfoResponse userInfoResponse = new UserInfoResponse();
        userInfoResponse.setUsername(userDetails.getUsername());
        userInfoResponse.setId(userDetails.getId());
        userInfoResponse.setEmail(userDetails.getEmail());
        userInfoResponse.setRoles(roles);
        loginResponseDto.setUserInfoResponse(userInfoResponse);
        return loginResponseDto;
    }

    @Override
    public RegisterResponseDto register(RegisterRequestDto registerRequestDto) {
        if(this.businessManager.checkEmailExists(registerRequestDto.getEmail())) {
            throw  new EmailExistsException("Email Already exception");
        }

        if(this.businessManager.checkPhoneExists(registerRequestDto.getMobile())) {
            throw  new PhoneNumberExistsException("Phone Number exists exception");
        }
        BusinessData businessData = registerDtoToModel(registerRequestDto);
        Set<String> strRoles = registerRequestDto.getRoles();
        businessData.setRoles(getRoles(strRoles));
        this.businessManager.addBusiness(businessData);
        LoginRequestDto loginRequestDto = new LoginRequestDto();
        loginRequestDto.setUserName(registerRequestDto.getEmail());
        loginRequestDto.setPassword(registerRequestDto.getPassword());
        RegisterResponseDto registerResponseDto = new RegisterResponseDto();
        LoginResponseDto loginResponse = login(loginRequestDto);
        registerResponseDto.setBusinessId(businessData.getBusinessId());
        registerResponseDto.setLoginResponseDto(loginResponse);
        return registerResponseDto;
    }

    @Override
    public LoginResponseDto logout() {
        Object principle = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (principle.toString() != "anonymousUser") {
            int userId = ((Principal) principle).getId();
            this.userService.deleteByUserId(userId);
        }
        ResponseCookie jwtCookie = this.jwtService.getCleanJwtCookie();
        ResponseCookie jwtRefreshCookie = this.jwtService.getCleanJwtRefreshCookie();
        LoginResponseDto loginResponseDto = new LoginResponseDto();
        loginResponseDto.setAccessToken(jwtCookie.toString());
        loginResponseDto.setRefreshToken(jwtRefreshCookie.toString());
        return loginResponseDto;
    }

    @Override
    public TokenRefreshResponse refreshToken(TokenRefreshRequest request) {
        String refreshToken = request.getRefreshToken();
        if ((refreshToken != null) && (refreshToken.length() > 0)) {
            return this.userService.findByToken(refreshToken)
                    .map(this.userService::verifyExpiration)
                    .map(RefreshToken::getBusinessData)
                    .map(user -> {
                        String token =  this.jwtService.generateTokenFromUsername(user.getEmail());
                        TokenRefreshResponse response = new TokenRefreshResponse();
                        response.setAccessToken(token);
                        response.setRefreshToken(refreshToken);
                        return response;
                    })
                    .orElseThrow(() -> new TokenRefreshException(refreshToken,
                            "Refresh token is not in database!"));

        }
        throw  new TokenRefreshException(refreshToken, "Refresh token is not in database!");
    }

    private Set<Role> getRoles(Set<String> strRoles){
        Set<Role> roles = new HashSet<>();
        if (strRoles == null) {
            Role userRole = this.userService.findByName(ERole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
            roles.add(userRole);
        } else {
            strRoles.forEach(role -> {
                switch (role) {
                    case "admin":
                        Role adminRole = this.userService.findByName(ERole.ROLE_ADMIN)
                                .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                        roles.add(adminRole);

                        break;

                    default:
                        Role userRole = this.userService.findByName(ERole.ROLE_USER)
                                .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                        roles.add(userRole);
                }
            });
        }
        return roles;
    }


    private UserDto modelToDto(BusinessData businessData) {
        UserDto userDto = new UserDto();
        userDto.setName(businessData.getBusinessName());
        userDto.setBusinessId(businessData.getBusinessId());
        userDto.setEmail(businessData.getEmail());
        userDto.setMobileNumber(businessData.getMobile());
        return userDto;
    }

    private BusinessData registerDtoToModel(RegisterRequestDto registerRequestDto) {
        BusinessData businessData = new BusinessData();
        businessData.setBusinessName(registerRequestDto.getBusinessName());
        businessData.setEmail(registerRequestDto.getEmail());
        businessData.setMobile(registerRequestDto.getMobile());
        businessData.setPassword(passwordEncoder.encode(registerRequestDto.getPassword()));
        businessData.setWhatsAppNumber(registerRequestDto.getWhatsAppNumber());
        businessData.setCountryId(registerRequestDto.getCountryId());
        return businessData;
    }
}
