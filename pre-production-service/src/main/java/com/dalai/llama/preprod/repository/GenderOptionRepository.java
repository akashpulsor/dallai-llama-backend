package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.GenderOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GenderOptionRepository extends JpaRepository<GenderOption, String> {

    List<GenderOption> findByActiveTrue();
}
