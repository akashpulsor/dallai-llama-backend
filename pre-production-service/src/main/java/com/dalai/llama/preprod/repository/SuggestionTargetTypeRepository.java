package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.SuggestionTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuggestionTargetTypeRepository extends JpaRepository<SuggestionTargetType, String> {

    List<SuggestionTargetType> findByActiveTrue();
}
