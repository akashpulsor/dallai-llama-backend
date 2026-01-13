package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.dto.*;
import com.dalai.llama.pbx.core.kamailio.KamailioClient;
import com.dalai.llama.pbx.core.model.CallRecord;
import com.dalai.llama.pbx.core.repository.CallRecordRepository;
import com.dalai.llama.pbx.core.util.CallIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {



        private final KamailioClient kamailioClient;
        private final CallRecordRepository callRepo;

        public CallRecord originateOutboundCall(OutboundCallRequest req) {

            log.info("Outbound call request: {}", req);

            // ---- 1. Save call record ----
            CallRecord record = CallRecord.builder()
                    .tenantId(req.getTenantId())
                    .callId(req.getCallId())
                    .direction("outbound")
                    .fromNumber(req.getFrom())
                    .toNumber(req.getTo())
                    .agentId(Long.valueOf(req.getAgentId()))
                    .agentUsername(req.getAgentUsername())
                    .startedAt(Instant.now())
                    .status("ringing")
                    .build();

            callRepo.save(record);

            // ---- 2. Originate call using Kamailio UAC ----
            kamailioClient.originate(
                    req.getKamailioRpcUrl(),
                    req.getCallId(),
                    req.getFrom(),
                    req.getTo(),
                    req.getSdpOffer()
            );

            return record;
        }

    public void rejectCall(String tenantId, String callId, RejectCallRequest req) {
        kamailioClient.reject(
                req.getRpcUrl(),
                callId,
                req.getFromTag(),
                req.getCode(),
                req.getReason()
        );
    }

    public void hangupCall(String tenantId, String callId, HangupCallRequest req) {
        CallRecord record = callRepo.findByTenantIdAndCallId(tenantId, callId);
        kamailioClient.hangup(req.getRpcUrl(), callId);
    }

    public void transferCall(String tenantId, String callId, TransferCallRequest req) {
        CallRecord record = callRepo.findByTenantIdAndCallId(tenantId, callId);
        kamailioClient.transfer(
                req.getRpcUrl(),
                callId,
                req.getFromTag(),
                req.getToTag(),
                req.getDestination()
        );
    }

    public void listenCall(String tenantId, String callId, ListenRequest req) {
        CallRecord record = callRepo.findByTenantIdAndCallId(tenantId, callId);
        kamailioClient.listen(
                req.getRpcUrl(),
                req.getSupervisorCallId(),
                callId,
                req.getSupervisorContact()
        );
    }

}
