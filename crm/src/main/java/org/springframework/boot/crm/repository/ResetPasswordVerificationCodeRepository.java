package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessClassification;
import org.springframework.boot.crm.entity.ResetPasswordVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResetPasswordVerificationCodeRepository extends JpaRepository<ResetPasswordVerificationCode, Integer> {

    Optional<ResetPasswordVerificationCode> findByVerificationCodeAndUserId(int resetPasswordVerificationCode,
                                                                               int userId);
}
