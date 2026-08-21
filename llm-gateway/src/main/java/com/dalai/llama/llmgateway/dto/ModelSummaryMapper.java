package com.dalai.llama.llmgateway.dto;

import com.dalai.llama.llmgateway.domain.entity.ModelMaster;
import org.mapstruct.Mapper;

/**
 * A straight 1:1 entity-to-DTO projection -- exactly what MapStruct is for. The multi-source
 * aggregations elsewhere (ChatResponse combines a job, a provider response, and a computed
 * cost; JobStatusResponse combines a job with no persisted content) are intentionally assembled
 * by hand in {@code LlmGatewayService} instead -- forcing those through a generated mapper would
 * hide the composition, not simplify it.
 */
@Mapper(componentModel = "spring")
public interface ModelSummaryMapper {

    ModelSummary toSummary(ModelMaster model);
}
