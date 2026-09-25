package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.BuiltinVoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuiltinVoiceRepository extends JpaRepository<BuiltinVoice, String> {

    List<BuiltinVoice> findByActiveTrue();

    List<BuiltinVoice> findByActiveTrueAndGender(String gender);

    java.util.Optional<BuiltinVoice> findByProviderIdAndProviderVoiceId(String providerId, String providerVoiceId);

    /** Duplicate-tolerant lookup for BuiltinVoiceSyncService. Returns every row for this
     * (provider, providerVoiceId); the service picks one and can clean up the rest. Existed
     * because a partial sync run inserted duplicate rows (friendly V80 voice_id
     * 'elevenlabs-sarah' plus an auto-generated 'elevenlabs-<providerVoiceId>' with the same
     * providerVoiceId), which then made the singular findBy... throw IncorrectResultSize on the
     * next sync. See V111 dedupe migration. */
    java.util.List<BuiltinVoice> findAllByProviderIdAndProviderVoiceIdOrderByVoiceIdAsc(
            String providerId, String providerVoiceId);
}
