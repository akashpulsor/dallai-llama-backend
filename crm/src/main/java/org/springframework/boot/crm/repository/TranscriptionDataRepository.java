package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.LeadData;
import org.springframework.boot.crm.entity.TranscriptionData;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.stereotype.Repository;

public interface TranscriptionDataRepository extends JpaRepository<TranscriptionData, Integer> {


    TranscriptionData findByBusinessIdAndCampaignRunIdAndCallId(int businessId, int campaignRunId, int callId);

    TranscriptionData findByCallId(int callId);
}
