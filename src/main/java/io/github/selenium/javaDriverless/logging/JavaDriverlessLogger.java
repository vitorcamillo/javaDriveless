package io.github.selenium.javaDriverless.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Centraliza os controles de log do JavaDriverless para uso local e em servidor.
 */
public final class JavaDriverlessLogger {

    public enum Level {
        OFF, ERROR, WARN, INFO, DEBUG, TRACE
    }

    private static final Level LEVEL = parseLevel(System.getenv("JAVA_DRIVERLESS_LOG_LEVEL"), Level.WARN);
    private static final boolean CHROME_LOGS = envBool("JAVA_DRIVERLESS_CHROME_LOGS", false);
    private static final boolean PROFILE_EVENTS = envBool("JAVA_DRIVERLESS_LOG_PROFILE_EVENTS", false);
    private static final boolean STARTUP_BANNER = envBool("JAVA_DRIVERLESS_LOG_STARTUP_BANNER", false);
    private static final boolean CHROME_TAIL_ON_ERROR = envBool("JAVA_DRIVERLESS_LOG_CHROME_TAIL_ON_ERROR", true);

    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", slf4jLevelName(effectiveSlf4jLevel()));
        }
        if (System.getProperty("org.slf4j.simpleLogger.showDateTime") == null) {
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        }
        if (System.getProperty("org.slf4j.simpleLogger.dateTimeFormat") == null) {
            System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        }
    }

    private JavaDriverlessLogger() {
    }

    public static Logger getLogger(Class<?> type) {
        return LoggerFactory.getLogger(type);
    }

    public static boolean isEnabled(Level level) {
        return LEVEL != Level.OFF && level.ordinal() <= LEVEL.ordinal();
    }

    public static boolean isChromeLogsEnabled() {
        return LEVEL != Level.OFF && (CHROME_LOGS || isEnabled(Level.TRACE));
    }

    public static boolean isProfileEventsEnabled() {
        return LEVEL != Level.OFF && (PROFILE_EVENTS || isEnabled(Level.DEBUG));
    }

    public static boolean isStartupBannerEnabled() {
        return LEVEL != Level.OFF && (STARTUP_BANNER || isEnabled(Level.DEBUG));
    }

    public static boolean isChromeTailOnErrorEnabled() {
        return CHROME_TAIL_ON_ERROR;
    }

    public static void error(Logger logger, String message, Object... args) {
        if (isEnabled(Level.ERROR)) {
            logger.error(message, args);
        }
    }

    public static void warn(Logger logger, String message, Object... args) {
        if (isEnabled(Level.WARN)) {
            logger.warn(message, args);
        }
    }

    public static void info(Logger logger, String message, Object... args) {
        if (isEnabled(Level.INFO)) {
            logger.info(message, args);
        }
    }

    public static void startup(Logger logger, String message, Object... args) {
        if (STARTUP_BANNER || isEnabled(Level.INFO)) {
            logger.info(message, args);
        }
    }

    public static void debug(Logger logger, String message, Object... args) {
        if (isEnabled(Level.DEBUG)) {
            logger.debug(message, args);
        }
    }

    public static void trace(Logger logger, String message, Object... args) {
        if (isEnabled(Level.TRACE)) {
            logger.trace(message, args);
        }
    }

    public static void profile(Logger logger, String message, Object... args) {
        if (isProfileEventsEnabled()) {
            logger.info(message, args);
        }
    }

    public static void chromeProcess(Logger logger, String message) {
        if (isChromeLogsEnabled()) {
            logger.debug("[ChromeProcess] {}", message);
        }
    }

    private static Level parseLevel(String raw, Level defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    private static boolean envBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }

    private static Level effectiveSlf4jLevel() {
        if (LEVEL == Level.OFF) {
            return Level.OFF;
        }
        if (CHROME_LOGS || isEnabled(Level.DEBUG)) {
            return Level.DEBUG;
        }
        if (PROFILE_EVENTS || STARTUP_BANNER || isEnabled(Level.INFO)) {
            return Level.INFO;
        }
        return LEVEL;
    }

    private static String slf4jLevelName(Level level) {
        if (level == Level.OFF) {
            return "off";
        }
        return level.name().toLowerCase(Locale.ROOT);
    }
}
