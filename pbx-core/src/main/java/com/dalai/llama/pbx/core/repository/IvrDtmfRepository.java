package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.IvrDtmfMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IvrDtmfRepository extends JpaRepository<IvrDtmfMap, Long> {

    List<IvrDtmfMap> findByNode_Id(Long nodeId);

    void deleteByNode_Id(Long nodeId);
}
