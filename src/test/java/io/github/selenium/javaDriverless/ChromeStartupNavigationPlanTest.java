package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromeStartupNavigationPlanTest {
    @Test
    void positionalDestinationIsDeferredAndNeverPassedToChrome() {
        var plan = ChromeStartupNavigationPlan.local(
                "about:blank", List.of("--flag=value", "https://example.invalid/private"));

        assertThat(plan.launchArguments()).containsExactly("--flag=value", "about:blank");
        assertThat(plan.deferredUrl()).isEqualTo("https://example.invalid/private");
    }

    @Test
    void configuredDestinationIsDeferredAndNeverPassedToChrome() {
        var plan = ChromeStartupNavigationPlan.local(
                "data:text/html,probe", List.of("--flag=value"));

        assertThat(plan.launchArguments()).containsExactly("--flag=value", "about:blank");
        assertThat(plan.deferredUrl()).isEqualTo("data:text/html,probe");
    }

    @Test
    void multipleDestinationsFailClosedBeforeLaunchWithoutEchoingThem() {
        assertThatThrownBy(() -> ChromeStartupNavigationPlan.local(
                "https://configured.invalid/secret",
                List.of("https://positional.invalid/secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiplos destinos")
                .hasMessageNotContaining("configured.invalid")
                .hasMessageNotContaining("positional.invalid")
                .hasMessageNotContaining("secret");
    }

    @Test
    void absolutePathIsAPositionalTargetInsteadOfAChromeSwitch() {
        var plan = ChromeStartupNavigationPlan.local(
                "about:blank", List.of("/opt/phantompass/start.html"));

        assertThat(plan.launchArguments()).containsExactly("about:blank");
        assertThat(plan.deferredUrl()).isEqualTo("/opt/phantompass/start.html");
    }
}
