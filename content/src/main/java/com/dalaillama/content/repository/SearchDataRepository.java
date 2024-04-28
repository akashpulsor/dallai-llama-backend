package com.dalaillama.content.repository;

import com.dalaillama.content.entity.SearchData;
import com.dalaillama.content.entity.StructureData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public interface SearchDataRepository extends JpaRepository<SearchData, Integer> {
}
