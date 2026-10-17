package com.dalai.llama.tenant.showcase.inspiration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspirationPinsTest {

    @Test
    void takesThePinIdFromAnyPinterestPinLinkAndLinksBackCanonically() {
        assertThat(InspirationPins.idFrom("https://www.pinterest.com/pin/1125968653541839/")).isEqualTo("1125968653541839");
        assertThat(InspirationPins.idFrom("https://in.pinterest.com/pin/1125968653541839/?nic=1")).isEqualTo("1125968653541839");
        assertThat(InspirationPins.idFrom("http://pinterest.co.uk/pin/987654321")).isEqualTo("987654321");
        assertThat(InspirationPins.idFrom(" 1125968653541839 ")).isEqualTo("1125968653541839");
        assertThat(InspirationPins.url("987654321")).isEqualTo("https://www.pinterest.com/pin/987654321/");
    }

    @Test
    void refusesAnythingThatIsNotAPin() {
        assertThatThrownBy(() -> InspirationPins.idFrom("https://pin.it/abc123")).hasMessageContaining("pin link");
        assertThatThrownBy(() -> InspirationPins.idFrom("https://www.youtube.com/watch?v=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InspirationPins.idFrom("https://evil.example/pin/123456789")).isInstanceOf(IllegalArgumentException.class);
    }
}
