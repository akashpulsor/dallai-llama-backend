package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.VideoModelFrameRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoModelFrameRateRepository extends JpaRepository<VideoModelFrameRate, Long> {

    List<VideoModelFrameRate> findByModelIdOrderByFpsAsc(String modelId);
}
