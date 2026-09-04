package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ReviewComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewCommentRepository extends JpaRepository<ReviewComment, UUID> {

    List<ReviewComment> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
