package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallStatusRepository extends JpaRepository<CallStatus, Integer> {
}
