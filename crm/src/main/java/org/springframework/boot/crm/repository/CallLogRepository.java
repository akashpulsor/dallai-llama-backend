package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CallLogRepository  extends JpaRepository<CallLog, Integer> {

    Optional<CallLog> findByCallTypeAndCampaignRunIdAndLeadId(String callType, int campaignRunId, int leadId);

    @Query("SELECT cl FROM call_log cl WHERE cl.campaignRunId = :campaignRunId ORDER BY cl.callLogId DESC, cl.startTime DESC")
    Page<CallLog> findByCampaignRunIdOrderByCallLogIdAndStartTime(
            @Param("campaignRunId") int campaignRunId,
            Pageable pageable
    );


    @Query("SELECT cl FROM call_log cl WHERE cl.campaignRunId = :campaignRunId ORDER BY cl.callLogId DESC, cl.startTime DESC")
    List<CallLog> findByCampaignRunIdOrderByCallLogIdAndStartTime(
            @Param("campaignRunId") int campaignRunId
    );

    //Find by call Sid
    @Query("SELECT cl FROM call_log cl WHERE cl.callSid = :callSid")
    Optional<CallLog> findByCallSid(@Param("callSid") String callSid);

}
