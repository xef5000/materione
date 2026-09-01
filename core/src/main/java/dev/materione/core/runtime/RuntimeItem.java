package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * One item as the runtime serves it (runtime-systems §3): what an
 * ItemStack factory needs, plus the definition hash the auto-updater
 * compares against stacks in the wild. Immutable; rebuilt wholesale by
 * each apply (shadow-swap), never mutated in place.
 *
 * <p>{@code synthetic} marks the auto-generated placement items of blocks
 * and furniture that declare no {@code item:} link — they live only in the
 * runtime registries (no file backs them), so browsers skip them as
 * standalone entries and hand them out through the block/furniture row.
 */
public record RuntimeItem(
        NamespacedId id,
        String material,
        NamespacedId modelId,
        String displayName,
        List<String> lore,
        String definitionHash,
        Food food,
        boolean synthetic) {

    public RuntimeItem {
        lore = List.copyOf(lore);
    }

    /** Pre-mechanics arity kept for tests and simple builders. */
    public RuntimeItem(
            NamespacedId id, String material, NamespacedId modelId,
            String displayName, List<String> lore, String definitionHash) {
        this(id, material, modelId, displayName, lore, definitionHash, null, false);
    }

    /** Pre-synthetic arity: constructs a real (authored) item. */
    public RuntimeItem(
            NamespacedId id, String material, NamespacedId modelId,
            String displayName, List<String> lore, String definitionHash, Food food) {
        this(id, material, modelId, displayName, lore, definitionHash, food, false);
    }

    /**
     * The food mechanic's stack-level projection (null without the
     * mechanic): applied as the vanilla food component when stacks are
     * built, so every factory path (recipes, drops, auto-update) yields an
     * edible stack with the same values.
     */
    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {}

    /**
     * Hash of everything players see — the auto-updater rebuilds a stack
     * when this changes; volatile state (durability, enchantments) is
     * preserved across the rebuild.
     */
    public static String hashOf(
            NamespacedId id, String material, NamespacedId modelId, String displayName, List<String> lore) {
        String canonical = String.join("|",
                id.toString(),
                material,
                modelId == null ? "-" : modelId.toString(),
                displayName == null ? "-" : displayName,
                String.join("\u0000", lore));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — unreachable
            return Integer.toHexString(canonical.hashCode());
        }
    }
}
