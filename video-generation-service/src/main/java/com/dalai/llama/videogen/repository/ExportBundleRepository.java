package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ExportBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExportBundleRepository extends JpaRepository<ExportBundle, UUID> {
}
