package dev.materione.api.platform;

import java.util.Set;

/**
 * Resolved-at-boot description of the running platform: one record, injected
 * everywhere version-sensitive code needs it. Consumers ask
 * {@link #supports(PlatformCapability)}; they never parse versions.
 */
public record PlatformDescriptor(
        String minecraftVersion,
        boolean folia,
        boolean geyserPresent,
        boolean viaVersionPresent,
        Set<PlatformCapability> capabilities) {

    public PlatformDescriptor {
        capabilities = Set.copyOf(capabilities);
    }

    public boolean supports(PlatformCapability capability) {
        return capabilities.contains(capability);
    }
}
