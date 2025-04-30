package org.springframework.boot.crm.controller;

import jakarta.validation.Valid;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
import org.springframework.boot.crm.entity.DalaiLlamaLeads;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.UserManager;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserManager userManager;

    private final BusinessManager businessManager;

    public AuthController(UserManager userManager, BusinessManager businessManager) {
        this.userManager = userManager;
        this.businessManager = businessManager;
    }

    @PostMapping("/interest")
    public DalaiLlamaLeads interest(@Valid @RequestBody DalaiLlamaLeadsDto dalaiLlamaLeadsDto){
        return this.businessManager.addDalaiLLamaLeads(dalaiLlamaLeadsDto);
    }

    //create controller to get interest in paginated way

    @GetMapping("/interest")
    public Page<DalaiLlamaLeads> getInterestPaginated(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<DalaiLlamaLeads> dalaiLlamaLeads = this.businessManager.getPaginatedDalaiLlamaLeads(page, size);
        return dalaiLlamaLeads;
    }

    @PostMapping("/login")
    public LoginResponseDto authenticateUser(@Valid @RequestBody LoginRequestDto loginRequestDto) {
        return this.userManager.login(loginRequestDto);
    }

    @PostMapping("/register")
    public RegisterResponseDto registerUser(@Valid @RequestBody RegisterRequestDto registerRequestDto) {
        return this.userManager.register(registerRequestDto);
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


    @GetMapping("/company-size")
    public List<BusinessSizeMasterDataDto> getCompanySizeMasterData() {
        return this.businessManager.getAllBusinessSizeMasterData();
    }

    @PostMapping("/verification-code")
    public VerificationCodeResponseDto getVerificationCode(VerificationCodeRequestDto verificationCodeRequestDto) {
        return this.userManager.sendVerificationCode(verificationCodeRequestDto);
    }


    @PostMapping("/verify-code")
    public String getVerificationCode(ValidateVerificationCodeRequestDto validateVerificationCodeRequestDto) {
        return this.userManager.verifyCode(validateVerificationCodeRequestDto);
    }

    @PostMapping("/update-password")
    public UserDto updatePassword(UpdatePasswordRequestDto updatePasswordRequestDto) {
        return this.userManager.updatePassword(updatePasswordRequestDto);
    }

    @GetMapping("/test")
    public String test(){
        return "app running";
    }
}
