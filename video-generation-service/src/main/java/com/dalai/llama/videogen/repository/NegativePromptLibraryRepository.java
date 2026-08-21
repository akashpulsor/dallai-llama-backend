package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.LibraryScope;
import com.dalai.llama.videogen.domain.entity.NegativePromptLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NegativePromptLibraryRepository extends JpaRepository<NegativePromptLibrary, Long> {

    List<NegativePromptLibrary> findByScopeAndProviderId(LibraryScope scope, String providerId);

    List<NegativePromptLibrary> findByScope(LibraryScope scope);
}
