package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;


@Data
@Entity(name = "reset_password_verification_code")
public class ResetPasswordVerificationCode {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="verification_code_id")
    private int verificationCodeId;

    @Column(name="verification_code")
    private int verificationCode;

    @Column(name="user_id")
    private int userId;

    @Column(name="expiry_time")
    private int expiryTime;

    @Embedded
    private SanitaryColumn sanitaryColumn;
}
