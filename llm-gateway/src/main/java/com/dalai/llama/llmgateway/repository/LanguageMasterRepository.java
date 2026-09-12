package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LanguageMasterRepository extends JpaRepository<LanguageMaster, String> {

    List<LanguageMaster> findByPlatformCode(String platformCode);
}
