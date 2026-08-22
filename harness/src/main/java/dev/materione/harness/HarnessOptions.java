package dev.materione.harness;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Harness configuration, resolved from system properties (injected by the
 * Gradle {@code integrationTest} task) with environment-variable overrides
 * ({@code MTR_HARNESS_BOOT_SECS} etc. for CI).
 */
public record HarnessOptions(
        Path paperJar,
        Path pluginJar,
        Path javaExecutable,
        Path workRoot,
        String expectedVersion,
        Duration bootTimeout,
        Duration commandTimeout,
        Duration stopTimeout,
        String brokenSettingsYaml,
        Path contentPacksDir) {

    public static HarnessOptions fromProperties() {
        return new HarnessOptions(
                requiredPath("paper.jar"),
                requiredPath("plugin.jar"),
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"),
                Path.of(optional("harness.workroot", "build/work")),
                optional("materione.version", "unknown"),
                seconds("boot", 300),
                seconds("command", 60),
                seconds("stop", 60),
                null,
                optionalPath("harness.contentPacks"));
    }

    public HarnessOptions withScenario(Path scenarioWorkRoot, String brokenSettings) {
        return new HarnessOptions(
                paperJar, pluginJar, javaExecutable, scenarioWorkRoot, expectedVersion,
                bootTimeout, commandTimeout, stopTimeout, brokenSettings, contentPacksDir);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path requiredPath(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("system property '" + key + "' not set — run via :harness:integrationTest");
        }
        return Path.of(value);
    }

    private static String optional(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path optionalPath(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Duration seconds(String name, int defaultSeconds) {
        String prop = System.getProperty("harness.timeout." + name + "Secs");
        String env = System.getenv("MTR_HARNESS_" + name.toUpperCase() + "_SECS");
        String raw = prop != null ? prop : env;
        return raw != null ? Duration.ofSeconds(Long.parseLong(raw.trim())) : Duration.ofSeconds(defaultSeconds);
    }
}
