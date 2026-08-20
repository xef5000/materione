package dev.materione.api.platform;

/** Result of the platform probe: supported with a descriptor, or not. */
public sealed interface ProbeResult {

    record Supported(PlatformDescriptor descriptor) implements ProbeResult {}

    /**
     * @param reason human-readable why (e.g. "found 1.21.3, floor is 1.21.4")
     * @param instructions what the operator should do next
     */
    record Unsupported(String reason, String instructions) implements ProbeResult {}
}
