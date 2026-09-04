package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.AbstractBatchJobPersistenceService;
import com.dalai.llama.preprod.domain.entity.ShotAssetBatchJob;
import com.dalai.llama.preprod.repository.ShotAssetBatchJobRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** All the actual transition logic (progress/attempt/advance/dead-letter) is job-lifecycle-
 * common's {@link AbstractBatchJobPersistenceService} -- this only supplies this service's own
 * repository and dead-letter sink, and the not-found exception type every other service here
 * already uses. */
@Service
public class ShotAssetBatchJobPersistenceService extends AbstractBatchJobPersistenceService<ShotAssetBatchJob> {

    public ShotAssetBatchJobPersistenceService(ShotAssetBatchJobRepository repository, ShotAssetBatchDeadLetterService deadLetterSink) {
        super(repository, deadLetterSink);
    }

    @Override
    protected RuntimeException notFound(UUID jobId) {
        return PreProductionException.notFound("No shot asset batch job " + jobId);
    }
}
