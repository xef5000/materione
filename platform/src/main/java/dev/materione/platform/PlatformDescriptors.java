package dev.materione.platform;

import dev.materione.api.platform.PlatformCapability;
import dev.materione.api.platform.PlatformDescriptor;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves a {@link PlatformDescriptor} from a parsed version + detected
 * hooks. The single implementation M0 ships (per roadmap); a second, newer
 * implementation arrives when a second supported line exists.
 */
public final class PlatformDescriptors {

    private PlatformDescriptors() {}

    public static PlatformDescriptor resolve(
            String minecraftVersion, boolean folia, boolean geyser, boolean via) {
        Set<PlatformCapability> capabilities = EnumSet.noneOf(PlatformCapability.class);
        capabilities.add(PlatformCapability.DISPLAY_ENTITIES); // 1.19.4+, below any supported server
        capabilities.add(PlatformCapability.DATA_COMPONENTS); // 1.20.5+, below the floor
        capabilities.add(PlatformCapability.EQUIPPABLE_ASSET); // 1.21.2+, below the floor
        capabilities.add(PlatformCapability.ITEM_MODELS); // 1.21.4+ == the floor itself
        capabilities.add(PlatformCapability.DIALOGS); // 1.21+, below the floor
        if (folia) {
            capabilities.add(PlatformCapability.FOLIA);
        }
        return new PlatformDescriptor(minecraftVersion, folia, geyser, via, capabilities);
    }
}
