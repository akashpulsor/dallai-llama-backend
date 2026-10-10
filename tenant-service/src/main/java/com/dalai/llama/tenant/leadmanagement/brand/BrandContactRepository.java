package com.dalai.llama.tenant.leadmanagement.brand;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandContactRepository extends JpaRepository<BrandContact, UUID> {

    Optional<BrandContact> findByEmail(String email);

    List<BrandContact> findByAutoPicksOptInTrue();
}
