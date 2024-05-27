package com.dalaillama.content.repository;

import com.dalaillama.content.entity.Article;
import com.dalaillama.content.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;


@Component
public interface PriceListRepository  extends JpaRepository<PriceList, Integer> {
}
