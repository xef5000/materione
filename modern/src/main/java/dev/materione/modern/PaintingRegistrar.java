package dev.materione.modern;

import dev.materione.content.paintings.PaintingDefinition;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.PaintingVariantKeys;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Art;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers every parsed painting as a {@code painting_variant} through
 * Paper's registry compose event — the Nexo model. The variants behave
 * exactly like datapack entries (they sync to clients during the
 * configuration phase) without anything being written into a world folder;
 * changes still require a restart, because compose fires once per boot.
 *
 * <p>Placeable variants additionally join the vanilla {@code placeable}
 * tag via the post-flatten tags event, replacing the datapack tag file.
 *
 * <p>This class references 26.x-era APIs ({@code compose()}); it must only
 * be entered when {@link RegistryCapabilities#hasCompose()} is true — the
 * gate lives in {@link MaterioneBootstrap}.
 */
final class PaintingRegistrar {

    private PaintingRegistrar() {}

    /**
     * Registers the compose and tags handlers; returns the tag keys queued
     * for the placeable tag. Per-entry failures are warned and skipped —
     * one bad variant never blocks the rest (D6).
     */
    static List<TypedKey<Art>> register(BootstrapContext context, List<PaintingDefinition> paintings) {
        var placeable = new ArrayList<TypedKey<Art>>();
        if (paintings.isEmpty()) {
            return placeable;
        }
        context.getLifecycleManager().registerEventHandler(
                RegistryEvents.PAINTING_VARIANT.compose().newHandler(event -> {
                    for (PaintingDefinition painting : paintings) {
                        try {
                            Key key = Key.key(painting.id().toString());
                            event.registry().register(PaintingVariantKeys.create(key), builder -> builder
                                    .author(Component.text(painting.author()))
                                    .title(Component.text(painting.title()))
                                    .assetId(Key.key(painting.assetId()))
                                    .width(painting.width())
                                    .height(painting.height()));
                            if (painting.placeable()) {
                                placeable.add(TypedKey.create(RegistryKey.PAINTING_VARIANT, key));
                            }
                        } catch (RuntimeException e) {
                            context.getLogger().warn(
                                    "Failed to register painting " + painting.id() + ": " + e.getMessage());
                        }
                    }
                }));
        if (!placeable.isEmpty()) {
            context.getLifecycleManager().registerEventHandler(
                    LifecycleEvents.TAGS.postFlatten(RegistryKey.PAINTING_VARIANT), event ->
                            event.registrar().addToTag(
                                    TagKey.create(RegistryKey.PAINTING_VARIANT, Key.key("placeable")),
                                    List.copyOf(placeable)));
        }
        return placeable;
    }
}
