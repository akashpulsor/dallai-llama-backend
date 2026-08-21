package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.ModelMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelMasterRepository extends JpaRepository<ModelMaster, String> {

    List<ModelMaster> findByTypeAndStatus(String type, String status);

    List<ModelMaster> findByStatus(String status);
}
