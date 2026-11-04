package io.github.selenium.javaDriverless.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import org.junit.jupiter.api.Test;

class PointerJitterLimitTest {

    @Test
    void hugeJitterRadiusIsCappedToAvoidOverflow() {
        Pointer pointer = new Pointer(mock(CDPCommandExecutor.class));
        pointer.setClickJitter(Integer.MAX_VALUE);
        assertThat(pointer.getClickJitter()).isEqualTo(Pointer.MAX_CLICK_JITTER);
        pointer.setClickJitter(-5);
        assertThat(pointer.getClickJitter()).isZero();
    }
}
