package org.springframework.boot.crm.service;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CallStatus;
import org.springframework.boot.crm.exceptions.CallLogNotFoundException;
import org.springframework.boot.crm.exceptions.CampaignRunNotFoundExceptions;
import org.springframework.boot.crm.repository.CallLogRepository;
import org.springframework.boot.crm.repository.CallStatusRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class CallLogService {

    private final CallLogRepository callLogRepository;
    private final CallStatusRepository callStatusRepository;


    public CallLogService(CallLogRepository callLogRepository, CallStatusRepository callStatusRepository) {
        this.callLogRepository = callLogRepository;
        this.callStatusRepository = callStatusRepository;
    }

    public CallLog createCallLog(String callType, int campaignRunId, int leadId, String callSid,
                                 String fromNumber, String toNumber) {
        CallLog callLog = new CallLog();
        callLog.setCallType(callType);
        callLog.setCampaignRunId(campaignRunId);
        callLog.setLeadId(leadId);
        callLog.setCallSid(callSid);
        callLog.setFromNumber(fromNumber);
        callLog.setToNumber(toNumber);
        callLog.setStartTime(LocalDateTime.now());

        // Set initial status
        addStatus(callLog, "INITIATED");

        return callLogRepository.save(callLog);
    }

    public CallLog getByCampaignRunIdAndLeadId(int campaignRunId, int leadId) {
        return new CallLog();
    }

    public CallLog updateStatus(int callLogId, String newStatus) {
        CallLog callLog = callLogRepository.findById(callLogId)
                .orElseThrow(() -> new EntityNotFoundException("CallLog not found"));

        addStatus(callLog, newStatus);

        // If status is terminal, set end time
        if (isTerminalStatus(newStatus)) {
            callLog.setEndTime(LocalDateTime.now());
        }

        return callLogRepository.save(callLog);
    }

    private void addStatus(CallLog callLog, String status) {
        CallStatus callStatus = new CallStatus();
        callStatus.setCallLog(callLog);
        callStatus.setStatus(status);
        callStatus.setTimestamp(LocalDateTime.now());
        callLog.getStatusHistory().add(callStatus);
    }

    private boolean isTerminalStatus(String status) {
        return Arrays.asList("COMPLETED", "FAILED", "NO_ANSWER").contains(status);
    }

    public CallLog getCallLog(int callLogId) {
        return callLogRepository.findById(callLogId)
                .orElseThrow(() -> new EntityNotFoundException("CallLog not found"));
    }


    public CallLog getCallLog(String callType, int campaignRunId, int leadId) {
        return this.callLogRepository.findByCallTypeAndCampaignRunIdAndLeadId(callType,campaignRunId,  leadId).
                orElseThrow(() -> new CallLogNotFoundException("Call log not found"));
    }

    public CallLog saveCallLog(CallLog callLog) {
        return callLogRepository.save(callLog);
    }

    public long callLogCount(int businessId, LocalDate startDate, LocalDate endDate) {
        return 0l;//callLogRepository.countTotalCalls(businessId, startDate, endDate);
    }
}
