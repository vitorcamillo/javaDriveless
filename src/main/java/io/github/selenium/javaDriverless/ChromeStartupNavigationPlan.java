package io.github.selenium.javaDriverless;

import java.util.ArrayList;
import java.util.List;

/** Mantem qualquer destino de startup fora do comando do Chrome ate o fingerprint estar pronto. */
final class ChromeStartupNavigationPlan {
    private static final String SAFE_INITIAL_PAGE = "about:blank";

    private ChromeStartupNavigationPlan() {}

    static Plan local(String configuredStartupUrl, List<String> configuredArguments) {
        List<String> launchArguments = new ArrayList<>();
        List<String> deferredTargets = new ArrayList<>();

        if (configuredArguments != null) {
            for (String argument : configuredArguments) {
                if (isSwitch(argument)) {
                    launchArguments.add(argument);
                } else if (!isSafeInitialPage(argument)) {
                    deferredTargets.add(requireTarget(argument));
                }
            }
        }

        if (!isSafeInitialPage(configuredStartupUrl) && !isBlank(configuredStartupUrl)) {
            deferredTargets.add(configuredStartupUrl.trim());
        }
        if (deferredTargets.size() > 1) {
            throw new IllegalStateException(
                    "Multiplos destinos de startup foram rejeitados antes do launch; "
                            + "use somente ChromeOptions.setStartupUrl");
        }

        // Uma unica page inerte torna deterministico qual target recebe o preflight e os overrides.
        launchArguments.add(SAFE_INITIAL_PAGE);
        String deferred = deferredTargets.isEmpty() ? null : deferredTargets.get(0);
        return new Plan(List.copyOf(launchArguments), deferred);
    }

    private static boolean isSwitch(String argument) {
        if (argument == null) return false;
        String trimmed = argument.trim();
        return trimmed.startsWith("-");
    }

    private static boolean isSafeInitialPage(String value) {
        return value != null && SAFE_INITIAL_PAGE.equalsIgnoreCase(value.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String requireTarget(String value) {
        if (isBlank(value)) {
            throw new IllegalStateException("Destino de startup vazio rejeitado antes do launch");
        }
        return value.trim();
    }

    record Plan(List<String> launchArguments, String deferredUrl) {}
}
