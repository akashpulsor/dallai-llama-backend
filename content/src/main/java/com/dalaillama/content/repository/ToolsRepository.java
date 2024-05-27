package com.dalaillama.content.repository;

import com.dalaillama.content.entity.Tools;
import com.dalaillama.content.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;


@Component
public interface ToolsRepository   extends JpaRepository<Tools, Integer> {
}
