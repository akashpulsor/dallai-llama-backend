package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LanguageMasterRepository extends JpaRepository<LanguageMaster, String> {

    List<LanguageMaster> findByPlatformCode(String platformCode);

    /** Read path for the language picker -- see V109: the seeded catalog has placeholder rows for
     * languages we don't have a real voice/TTS model for, and those get gated by active=false so
     * creators only see codes we can actually render end-to-end. */
    List<LanguageMaster> findByActiveTrue();
}
