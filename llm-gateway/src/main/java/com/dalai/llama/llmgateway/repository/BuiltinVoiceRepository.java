package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.BuiltinVoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuiltinVoiceRepository extends JpaRepository<BuiltinVoice, String> {

    List<BuiltinVoice> findByActiveTrue();

    List<BuiltinVoice> findByActiveTrueAndGender(String gender);

    java.util.Optional<BuiltinVoice> findByProviderIdAndProviderVoiceId(String providerId, String providerVoiceId);
}
