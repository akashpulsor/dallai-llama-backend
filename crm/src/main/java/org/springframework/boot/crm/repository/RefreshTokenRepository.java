package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BusinessData;
import org.springframework.boot.crm.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository   extends JpaRepository<RefreshToken, Integer> {
    Optional<RefreshToken> findByToken(String token);

    RefreshToken findByBusinessData(BusinessData businessData);

    @Modifying
    int deleteByBusinessData(BusinessData businessData);
}
