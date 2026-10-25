package dev.materione.platform.items;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.ItemModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The platform's ItemStack surface (runtime-systems §3): identity is a PDC
 * tag, not a model predicate — the item behaves correctly in vanilla
 * contexts (hoppers, anvils, crafting grids). A stack is
 * {@code material + item_model component + PDC {materione:id}}.
 *
 * <p>Lives in {@code platform/} because components are version-aware
 * (D10): the item_model component is 1.21.4+ surface; older idioms exist
 * only on the importer path (M7). Depends on nothing internal — callers
 * translate their runtime model into {@link StackSpec}.
 */
public final class MaterioneStacks {

    /** PDC key carrying the Materione identity on every stack. */
    public static final String ID_KEY = "id";

    /**
     * The food-mechanic projection: applied as the vanilla food component
     * at build time so the stack is edible with these values (1.21.4 API
     * surface — {@code ItemMeta#setFood}). Consume-side effects ride the
     * mechanic listener, not the component.
     */
    public record StackFood(int nutrition, float saturation, boolean canAlwaysEat) {}

    /**
     * Everything the factory needs for one item appearance — the
     * version-agnostic projection of a runtime item.
     */
    public record StackSpec(
            NamespacedId id,
            String material,
            NamespacedId modelId,
            String displayName,
            List<String> lore,
            StackFood food,
            String jukeboxSong,
            List<ItemModifier> itemModifiers) {

        public StackSpec {
            lore = List.copyOf(lore);
            itemModifiers = List.copyOf(itemModifiers);
        }

        /** Compatibility constructor for callers without extension item hooks. */
        public StackSpec(
                NamespacedId id, String material, NamespacedId modelId,
                String displayName, List<String> lore, StackFood food, String jukeboxSong) {
            this(id, material, modelId, displayName, lore, food, jukeboxSong, List.of());
        }

        /** Pre-food arity kept for callers that carry no mechanic state. */
        public StackSpec(
                NamespacedId id,
                String material,
                NamespacedId modelId,
                String displayName,
                List<String> lore) {
            this(id, material, modelId, displayName, lore, null, null);
        }

        /** Pre-song arity: items without a jukebox song. */
        public StackSpec(
                NamespacedId id,
                String material,
                NamespacedId modelId,
                String displayName,
                List<String> lore,
                StackFood food) {
            this(id, material, modelId, displayName, lore, food, null);
        }
    }

    private MaterioneStacks() {}

    /** The spec for one runtime item — the single RuntimeItem→StackSpec projection. */
    public static StackSpec specOf(dev.materione.core.runtime.RuntimeItem item) {
        return new StackSpec(
                item.id(),
                item.material(),
                item.modelId(),
                item.displayName(),
                item.lore(),
                item.food() == null ? null
                        : new StackFood(
                                item.food().nutrition(), item.food().saturation(),
                                item.food().canAlwaysEat()),
                item.jukeboxSong(), item.itemModifiers());
    }

    /** Builds the stack for a spec (the apply-stage factory output). */
    public static ItemStack build(StackSpec item) {
        Material material = Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) {
            // hosts without an item form (tripwire, technical stems) and bad
            // authored materials would throw here — paper renders the custom
            // model just the same, and every placement path is PDC-driven
            material = Material.PAPER;
        }
        ItemStack stack = new ItemStack(material);
        stack.editMeta(MetaEditors.forSpec(item));
        item.itemModifiers().forEach(modifier -> modifier.apply(stack));
        return stack;
    }

    /** Re-applies appearance components to an existing stack (auto-updater). */
    public static void refresh(ItemStack stack, StackSpec item) {
        stack.editMeta(MetaEditors.forSpec(item));
        item.itemModifiers().forEach(modifier -> modifier.apply(stack));
    }

    /** Reads the Materione identity from a stack (the ~10–50 µs cold-detect path). */
    public static Optional<NamespacedId> identityOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String raw = meta.getPersistentDataContainer()
                .get(key(), org.bukkit.persistence.PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(NamespacedId.parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // foreign/corrupt tag: treat as vanilla
        }
    }

    static org.bukkit.NamespacedKey key() {
        return new org.bukkit.NamespacedKey("materione", ID_KEY);
    }

    /** Meta edits shared by build and refresh — and ONLY ours: volatile state survives. */
    private static final class MetaEditors {

        static java.util.function.Consumer<ItemMeta> forSpec(StackSpec item) {
            return meta -> {
                meta.getPersistentDataContainer()
                        .set(key(), org.bukkit.persistence.PersistentDataType.STRING, item.id().toString());
                if (item.modelId() != null) {
                    // 1.21.4 item_model component (Bukkit surface, ItemMeta#setItemModel).
                    // Its value is the ITEM DEFINITION id — the client resolves it to
                    // assets/<ns>/items/<value>.json, and the compiler keys definitions
                    // by the item's own id (ItemType#compile); modelId only gates
                    // whether a definition exists (material-only items stay vanilla).
                    meta.setItemModel(new org.bukkit.NamespacedKey(
                            item.id().namespace(), item.id().value()));
                }
                if (item.displayName() != null && !item.displayName().isBlank()) {
                    meta.displayName(legacy(item.displayName()));
                }
                if (!item.lore().isEmpty()) {
                    List<Component> lore = new ArrayList<>();
                    for (String line : item.lore()) {
                        lore.add(legacy(line));
                    }
                    meta.lore(lore);
                }
                if (item.food() != null) {
                    org.bukkit.inventory.meta.components.FoodComponent food = meta.getFood();
                    food.setNutrition(item.food().nutrition());
                    food.setSaturation(item.food().saturation());
                    food.setCanAlwaysEat(item.food().canAlwaysEat());
                    meta.setFood(food);
                }
                if (item.jukeboxSong() != null) {
                    // the song lives in the generated datapack — until the
                    // restart that loads it, applying the component fails, and
                    // the disc stays silent rather than breaking the stack
                    try {
                        NamespacedId song = NamespacedId.parse(item.jukeboxSong());
                        var playable = meta.getJukeboxPlayable();
                        playable.setSongKey(new org.bukkit.NamespacedKey(song.namespace(), song.value()));
                        playable.setShowInTooltip(true);
                        meta.setJukeboxPlayable(playable);
                    } catch (RuntimeException rejected) {
                        // unknown registry key (datapack not loaded yet): skip quietly
                    }
                }
            };
        }

        private static Component legacy(String text) {
            // glyph tags resolve author-intent text without a permission gate
            dev.materione.core.runtime.RuntimeGlyphs glyphs =
                    dev.materione.platform.text.GlyphText.glyphs();
            if (glyphs == null || glyphs.isEmpty()) {
                return dev.materione.platform.text.ItemText.legacy(text);
            }
            return dev.materione.platform.text.GlyphText.resolve(
                    text, glyphs, dev.materione.platform.text.GlyphText.Permissions.ALLOW_ALL);
        }
    }
}
