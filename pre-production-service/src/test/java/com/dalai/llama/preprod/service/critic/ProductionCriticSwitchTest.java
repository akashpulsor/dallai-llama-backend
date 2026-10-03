package com.dalai.llama.preprod.service.critic;

import com.dalai.llama.preprod.domain.entity.ProductionCriticConfig;
import com.dalai.llama.preprod.repository.ProductionCriticConfigRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionCriticSwitchTest {

    private final ProductionCriticConfigRepository repository = mock(ProductionCriticConfigRepository.class);
    private final ProductionCriticSwitch criticSwitch = new ProductionCriticSwitch(repository);

    @Test
    void criticsRunUntilOpsSwitchesThemOff() {
        assertThat(criticSwitch.enabled()).isTrue();
    }

    @Test
    void opsCanSwitchThemOff() {
        when(repository.findById(ProductionCriticConfig.SINGLETON_ID)).thenReturn(Optional.of(
                new ProductionCriticConfig(ProductionCriticConfig.SINGLETON_ID, false, OffsetDateTime.now())));
        assertThat(criticSwitch.enabled()).isFalse();
    }
}
