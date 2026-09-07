package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.BuiltinVoiceLanguage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuiltinVoiceLanguageRepository extends JpaRepository<BuiltinVoiceLanguage, BuiltinVoiceLanguage.Id> {

    List<BuiltinVoiceLanguage> findByIdLanguageCode(String languageCode);

    List<BuiltinVoiceLanguage> findByIdVoiceIdIn(List<String> voiceIds);
}
