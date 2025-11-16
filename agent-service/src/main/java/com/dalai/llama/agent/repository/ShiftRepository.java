package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long> {
    List<Shift> findByAgentId(Long agentId);
    List<Shift> findByAgentIdAndEndAtIsNull(Long agentId);
}