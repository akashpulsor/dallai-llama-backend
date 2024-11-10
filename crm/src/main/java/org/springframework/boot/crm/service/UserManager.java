package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessData;

import java.util.List;

public interface UserManager {

    UserDto createUser(BusinessData businessData);

    LoginResponseDto login(LoginRequestDto loginRequestDto);

    LoginResponseDto logout();

    TokenRefreshResponse refreshToken(TokenRefreshRequest request);

    RegisterResponseDto register(RegisterRequestDto registerRequestDto);

    VerificationCodeResponseDto sendVerificationCode(VerificationCodeRequestDto verificationCodeRequestDto);

    UserDto updatePassword(UpdatePasswordRequestDto updatePasswordRequestDto);

}
