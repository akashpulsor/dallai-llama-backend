package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallLogRepository  extends JpaRepository<CallLog, Integer> {
}
