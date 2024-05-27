package com.dalaillama.content.repository;

import com.dalaillama.content.entity.Llm;
import com.dalaillama.content.entity.PublishDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public interface LlmRepository   extends JpaRepository<Llm, Integer> {
}
