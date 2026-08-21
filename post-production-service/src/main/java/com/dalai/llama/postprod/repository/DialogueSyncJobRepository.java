package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.DialogueSyncJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DialogueSyncJobRepository extends JpaRepository<DialogueSyncJob, UUID> {
}
