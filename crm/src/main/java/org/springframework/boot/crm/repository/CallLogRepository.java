package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CallLogRepository  extends JpaRepository<CallLog, Integer> {

    Optional<CallLog> findByCallTypeAndCampaignRunIdAndLeadId(String callType, int campaignRunId, int leadId);
}
