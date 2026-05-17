package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorTrendDump;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreatorTrendDumpRepository extends JpaRepository<CreatorTrendDump, UUID> {
}
