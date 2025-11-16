package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRecordRepository extends JpaRepository<CallRecord, String> {
    List<CallRecord> findByTenantId(String tenantId);
    CallRecord findByTenantIdAndCallId(String tenantId, String callId);
}
