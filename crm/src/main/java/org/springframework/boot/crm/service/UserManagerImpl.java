package org.springframework.boot.crm.service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.boot.crm.exceptions.*;
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

@Slf4j
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
        LoginResponseDto loginResponseDto = new LoginResponseDto();
        loginResponseDto.setAccessToken(jwtToken);
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
        log.info("Register Request data - {}", registerRequestDto);
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
    public VerificationCodeResponseDto sendVerificationCode(VerificationCodeRequestDto verificationCodeRequestDto) {
        if(!this.businessManager.checkEmailExists(verificationCodeRequestDto.getEmail())){
            log.error("Email is not registered");
            throw new UnregisteredEmailException("Email not registered");
        }
        VerificationCodeResponseDto verificationCodeResponseDto = new VerificationCodeResponseDto();
        int number = 100000 + (int)(Math.random() * 900000);
        verificationCodeResponseDto.setVerificationCode(123456);
        //TODO send email not doing it as I don't have email grid acess
        return verificationCodeResponseDto;
    }

    //TODO implement actual one
    public String verifyCode(ValidateVerificationCodeRequestDto verificationCodeRequestDto) {
        if(!this.businessManager.checkEmailExists(verificationCodeRequestDto.getEmail())){
            log.error("Email is not registered");
            throw new UnregisteredEmailException("Email not registered");
        }
        BusinessData businessData = this.businessManager.getBusinessByEmail(verificationCodeRequestDto.getEmail());
        boolean invalidCode =mismatchedVerificationCode(verificationCodeRequestDto.getVerificationCode(), 123456);
        boolean checkExpiry = expiredVerificationCode(verificationCodeRequestDto.getVerificationCode(), 123456);

        if(!invalidCode) throw  new InvalidVerificationCodeException("Invalid verification code");
        if(!checkExpiry) throw  new ExpiredVerificationCodeException("expired verification code");

        //ResetPasswordVerificationCode verificationCode = this.userService.findResetPasswordVerificationCode(verificationCodeRequestDto.getVerificationCode(), businessData.getBusinessId());

        //TODO send email not doing it as I don't have email grid acess
        return "success";
    }

    private boolean mismatchedVerificationCode(int sentCode, int lastCode){
        if (sentCode!=lastCode) {
            return false;
        }
        return true;
    }
    private boolean expiredVerificationCode(int sentCode, int lastCode){
        if (sentCode!=lastCode) {
            return false;
        }
        return true;
    }
    
    

    @Override
    public UserDto updatePassword(UpdatePasswordRequestDto updatePasswordRequestDto) {
        if(!updatePasswordRequestDto.getOldPassword().equals(updatePasswordRequestDto.getNewPassword())){
            throw  new PasswordMismatchException("Passwords do not match");
        }
        BusinessData businessData = this.businessManager.getBusinessByEmail(updatePasswordRequestDto.getEmail());
        businessData.setPassword(passwordEncoder.encode(updatePasswordRequestDto.getNewPassword()));
        return modelToDto(this.businessManager.addBusiness(businessData));
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
        LoginResponseDto loginResponseDto = new LoginResponseDto();
        loginResponseDto.setAccessToken(jwtCookie.toString());
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
        if(registerRequestDto.getName()!= null ) businessData.setName(registerRequestDto.getName());
        if(registerRequestDto.getBusinessName()!= null ) businessData.setBusinessName(registerRequestDto.getBusinessName());
        if(registerRequestDto.getEmail()!= null ) businessData.setEmail(registerRequestDto.getEmail());
        if(registerRequestDto.getMobile()!= null )businessData.setMobile(registerRequestDto.getMobile());
        if(registerRequestDto.getPassword()!= null ) businessData.setPassword(passwordEncoder.encode(registerRequestDto.getPassword()));
        if(registerRequestDto.getCountryCallingCode()!= null ) businessData.setCountryDialingCode(registerRequestDto.getCountryCallingCode());
        if(registerRequestDto.getCountryCode()!= null ) businessData.setCountryCode(registerRequestDto.getCountryCode());
        if(registerRequestDto.getBasicActivityDescription()!= null ) businessData.setBasicActivityDescription(registerRequestDto.getBasicActivityDescription());
        if(registerRequestDto.getCompanySize()!= 0 )  businessData.setBusinessSizeId(registerRequestDto.getCompanySize());
        businessData.setAccountNonLocked(true);
        businessData.setActive(true);
        businessData.setAccountNonExpired(true);
        businessData.setCredentialsNonExpired(true);
        return businessData;
    }
}
