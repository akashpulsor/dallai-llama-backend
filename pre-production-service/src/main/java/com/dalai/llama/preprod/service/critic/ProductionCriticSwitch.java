package com.dalai.llama.preprod.service.critic;

import com.dalai.llama.preprod.domain.entity.ProductionCriticConfig;
import com.dalai.llama.preprod.repository.ProductionCriticConfigRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Whether production's critics run: the script critic, the camera- and lighting-plan critics, and
 * the per-shot pre-flight critique in critic-service. Off, each critic step passes its plan through
 * untouched without calling a model, so every plan is the first attempt -- which is what ops
 * switches it off to see, in cost and in output. Read on every critic step (one indexed row), so a
 * change applies to the next step without a restart. On until ops says otherwise.
 */
@Component
public class ProductionCriticSwitch {

    private final ProductionCriticConfigRepository repository;

    public ProductionCriticSwitch(ProductionCriticConfigRepository repository) {
        this.repository = repository;
    }

    public boolean enabled() {
        return repository.findById(ProductionCriticConfig.SINGLETON_ID)
                .map(ProductionCriticConfig::isEnabled)
                .orElse(true);
    }

    @Transactional
    public boolean set(boolean enabled) {
        repository.save(new ProductionCriticConfig(ProductionCriticConfig.SINGLETON_ID, enabled, OffsetDateTime.now()));
        return enabled;
    }
}
