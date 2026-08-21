package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.entity.ModelMaster;
import com.dalai.llama.llmgateway.domain.entity.RateCard;

public record RoutedModel(ModelMaster model, RateCard rateCard) {
}
