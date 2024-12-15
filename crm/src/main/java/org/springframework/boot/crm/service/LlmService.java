package org.springframework.boot.crm.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.repository.LlmDataRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class LlmService {

    private final LlmDataRepository llmDataRepository;

    public LlmService(LlmDataRepository llmDataRepository) {
        this.llmDataRepository = llmDataRepository;
    }


    public LlmData addLlmData(LlmData llmData) {
        return llmDataRepository.save(llmData);
    }

    public List<LlmData> getLlmData(int businessId) {
        return llmDataRepository.findByBusinessId(businessId);
    }
}
