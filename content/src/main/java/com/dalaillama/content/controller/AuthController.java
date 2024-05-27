package com.dalaillama.content.controller;


import com.dalaillama.content.dto.*;
import com.dalaillama.content.service.UserManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserManager userManager;

    public AuthController(UserManager userManager) {
        this.userManager = userManager;
    }

    @PostMapping("/login")
    public LoginResponseDto authenticateUser(@Valid @RequestBody LoginRequestDto loginRequestDto) {
        return this.userManager.login(loginRequestDto);
    }

    @PostMapping("/register")
    public LoginResponseDto registerUser(@Valid @RequestBody SignUpRequestDto signUpRequestDto) {
        return this.userManager.signUp(signUpRequestDto);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logoutUser() {
        LoginResponseDto loginResponseDto = this.userManager.logout();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, loginResponseDto.getAccessToken())
                .header(HttpHeaders.SET_COOKIE, loginResponseDto.getRefreshToken())
                .body(new MessageResponse("You've been signed out!"));
    }

    @PostMapping("/refresh-token")
    public TokenRefreshResponse refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return this.userManager.refreshToken(request);
    }
}
