package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.AspectRatioOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AspectRatioOptionRepository extends JpaRepository<AspectRatioOption, String> {

    List<AspectRatioOption> findByActiveTrue();
}
