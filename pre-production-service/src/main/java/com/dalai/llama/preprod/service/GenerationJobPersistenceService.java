package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.AbstractJobLifecycleService;
import com.dalai.llama.preprod.domain.entity.GenerationJob;
import com.dalai.llama.preprod.repository.GenerationJobRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GenerationJobPersistenceService extends AbstractJobLifecycleService<GenerationJob> {

    public GenerationJobPersistenceService(GenerationJobRepository repository) {
        super(repository);
    }

    @Override
    protected RuntimeException notFound(UUID jobId) {
        return PreProductionException.notFound("No generation job " + jobId);
    }
}
