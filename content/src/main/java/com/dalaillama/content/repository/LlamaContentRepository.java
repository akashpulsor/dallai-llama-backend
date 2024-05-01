package com.dalaillama.content.repository;

import com.dalaillama.content.entity.LlamaContent;
import com.dalaillama.content.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface LlamaContentRepository  extends JpaRepository<LlamaContent, Integer> {
}
