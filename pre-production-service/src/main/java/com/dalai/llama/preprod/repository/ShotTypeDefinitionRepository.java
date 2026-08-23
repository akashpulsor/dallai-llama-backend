package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotTypeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShotTypeDefinitionRepository extends JpaRepository<ShotTypeDefinition, String> {

    List<ShotTypeDefinition> findByActiveTrue();
}
