package com.dalaillama.content.service;

import com.dalaillama.content.dto.*;

import java.util.List;

public interface UserManager {


    UserDto createUser(UserDto userDto);

    LoginResponseDto login(LoginRequestDto loginRequestDto);

    UserDto signUp(SignUpRequestDto signUpRequestDto);

    UserDto getUserById(long id);

    LoginResponseDto logout();

    TokenRefreshResponse refreshToken(TokenRefreshRequest request);

    List<UserHistoryDto> getUserHistory(int userId);

    UserDto getUser(int userId);

    UserDto updateUser(UserDto userDto);
}
