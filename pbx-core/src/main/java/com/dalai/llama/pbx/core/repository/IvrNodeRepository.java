package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.IvrNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IvrNodeRepository extends JpaRepository<IvrNode, Long> {

    List<IvrNode> findByTenantId(String tenantId);

    Optional<IvrNode> findByTenantIdAndNodeId(String tenantId, String nodeId);

    void deleteByTenantIdAndNodeId(String tenantId, String nodeId);
}
