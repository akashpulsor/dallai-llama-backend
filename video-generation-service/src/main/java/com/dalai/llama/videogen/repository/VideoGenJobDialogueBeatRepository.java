package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.VideoGenJobDialogueBeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoGenJobDialogueBeatRepository extends JpaRepository<VideoGenJobDialogueBeat, UUID> {

    List<VideoGenJobDialogueBeat> findByJobIdOrderByOrderIndexAsc(UUID jobId);
}
