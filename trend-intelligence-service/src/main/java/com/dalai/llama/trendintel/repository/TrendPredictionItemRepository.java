package com.dalai.llama.trendintel.repository;

import com.dalai.llama.trendintel.domain.entity.TrendPredictionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrendPredictionItemRepository extends JpaRepository<TrendPredictionItem, UUID> {

    List<TrendPredictionItem> findByReportIdOrderByItemOrderAsc(UUID reportId);
}
