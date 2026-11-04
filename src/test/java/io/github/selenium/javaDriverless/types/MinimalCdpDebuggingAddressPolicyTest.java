package io.github.selenium.javaDriverless.types;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class MinimalCdpDebuggingAddressPolicyTest {

    @Test
    void acceptsLoopbackAddressesAndAbsenceOfTheSwitch() {
        for (String argument : List.of(
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-address=localhost",
                "--remote-debugging-address=::1",
                "--remote-debugging-address=[::1]",
                "--remote-debugging-address= 127.0.0.1 ")) {
            assertThatCode(() -> MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(
                    List.of(argument)))
                    .doesNotThrowAnyException();
        }

        assertThatCode(() -> MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(
                List.of("--headless=new")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsNullArgumentList() {
        assertThatCode(() -> MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonLoopbackAddressesWithoutEchoingTheValue() {
        // O valor recebido nunca pode voltar na mensagem
        for (String address : List.of("0.0.0.0", "192.168.0.10")) {
            assertThatThrownBy(() -> MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(
                    List.of("--remote-debugging-address=" + address)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback")
                    .hasMessageNotContaining(address)
                    .hasMessageNotContaining("0.0.0.0");
        }
    }

    @Test
    void rejectsEmptyUnspecifiedAndAliasedSwitchForms() {
        // "::" não entra na asserção de eco porque a própria mensagem cita "::1"
        for (String argument : List.of(
                "--remote-debugging-address=::",
                "--remote-debugging-address=",
                "--remote-debugging-address",
                "-remote-debugging-address=0.0.0.0",
                "--REMOTE-DEBUGGING-ADDRESS=0.0.0.0")) {
            assertThatThrownBy(() -> MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(
                    List.of(argument)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback")
                    .hasMessageNotContaining("0.0.0.0");
        }
    }
}
