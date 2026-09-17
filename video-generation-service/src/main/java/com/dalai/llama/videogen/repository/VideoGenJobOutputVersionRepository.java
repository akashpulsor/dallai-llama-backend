package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.VideoGenJobOutputVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoGenJobOutputVersionRepository extends JpaRepository<VideoGenJobOutputVersion, UUID> {

    /** Every clip this shot has had, newest first. */
    List<VideoGenJobOutputVersion> findByJobIdOrderBySupersededAtDesc(UUID jobId);
}
