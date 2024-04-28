package com.dalaillama.content.repository;

import com.dalaillama.content.entity.StructureData;
import com.dalaillama.content.entity.Tools;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public interface StructureDataRepository    extends JpaRepository<StructureData, Integer> {
}
