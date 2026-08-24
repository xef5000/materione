package dev.materione.platform.recipes;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRecipe;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Applies recipes through the platform's recipe API (runtime-systems §9):
 * re-registers the recipe set of one apply, then resyncs the recipe book
 * (post-swap reconciliation, plugin.md §4.5). Recipe ids never collide
 * with {@code minecraft:} — ours are {@code <pack>:<name>} keys by
 * construction. Runs on the global thread (apply stage).
 */
public final class RecipeApplier {

    /** What one apply did, for the pre-apply report and doctor. */
    public record Outcome(int registered, int removed, int resyncedPlayers) {}

    /** Recipe keys this applier currently owns — removed wholesale before re-add. */
    private final Set<NamespacedKey> managed = new HashSet<>();

    /**
     * Swaps the server's materione recipe set to {@code registries}. Never
     * throws past a single recipe: a broken recipe is skipped, the rest
     * apply (fail-soft, D6).
     */
    public Outcome apply(RuntimeRegistries registries) {
        int removed = 0;
        List<NamespacedKey> previousKeys = new ArrayList<>(managed);
        for (NamespacedKey key : previousKeys) {
            if (Bukkit.removeRecipe(key)) {
                removed++;
            }
            managed.remove(key);
        }
        int registered = 0;
        List<NamespacedKey> keys = new ArrayList<>();
        for (RuntimeRecipe recipe : registries.recipes()) {
            NamespacedKey key = keyOf(recipe.id());
            try {
                register(key, recipe, registries);
                managed.add(key);
                keys.add(key);
                registered++;
            } catch (RuntimeException e) {
                // one broken recipe never blocks the rest (runtime-systems §9)
                Bukkit.getLogger().warning("[Materione] recipe " + recipe.id()
                        + " skipped: " + e.getMessage());
            }
        }
        int resynced = resync(previousKeys, keys);
        return new Outcome(registered, removed, resynced);
    }

    private static void register(NamespacedKey key, RuntimeRecipe recipe, RuntimeRegistries registries) {
        ItemStack result = stackOf(recipe.result(), registries);
        switch (recipe.kind()) {
            case "shaped" -> {
                ShapedRecipe shaped = new ShapedRecipe(key, result);
                shaped.shape(recipe.shape().toArray(String[]::new));
                recipe.shapedIngredients().forEach((character, ingredientId) ->
                        shaped.setIngredient(character, choiceOf(ingredientId, registries)));
                Bukkit.addRecipe(shaped);
            }
            case "shapeless" -> {
                ShapelessRecipe shapeless = new ShapelessRecipe(key, result);
                for (NamespacedId ingredient : recipe.ingredients()) {
                    shapeless.addIngredient(choiceOf(ingredient, registries));
                }
                Bukkit.addRecipe(shapeless);
            }
            case "furnace" -> {
                RecipeChoice input = choiceOf(recipe.ingredients().get(0), registries);
                FurnaceRecipe furnace = new FurnaceRecipe(key, result, input,
                        recipe.experience(), recipe.cookingTime());
                Bukkit.addRecipe(furnace);
            }
            default -> throw new IllegalArgumentException("unknown recipe kind " + recipe.kind());
        }
    }

    /** Materione items match exactly (PDC-tagged stacks); vanilla ids by material. */
    private static RecipeChoice choiceOf(NamespacedId id, RuntimeRegistries registries) {
        RuntimeItem item = registries.item(id).orElse(null);
        if (item != null) {
            return new RecipeChoice.ExactChoice(MaterioneStacks.build(MaterioneStacks.specOf(item)));
        }
        if (id.isReservedNamespace()) {
            Material material = Material.matchMaterial(id.toString());
            if (material == null) {
                throw new IllegalArgumentException("unknown vanilla ingredient " + id);
            }
            return new RecipeChoice.MaterialChoice(material);
        }
        throw new IllegalArgumentException("ingredient " + id + " is not a live item");
    }

    private static ItemStack stackOf(NamespacedId id, RuntimeRegistries registries) {
        RuntimeItem item = registries.item(id).orElse(null);
        if (item != null) {
            return MaterioneStacks.build(MaterioneStacks.specOf(item));
        }
        if (id.isReservedNamespace()) {
            Material material = Material.matchMaterial(id.toString());
            if (material != null) {
                return new ItemStack(material);
            }
        }
        throw new IllegalArgumentException("result " + id + " is not a live item");
    }

    /** Recipe book resync: players see the new set immediately (§4.5). */
    private static int resync(List<NamespacedKey> previousKeys, List<NamespacedKey> keys) {
        int players = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!previousKeys.isEmpty()) {
                player.undiscoverRecipes(previousKeys);
            }
            if (!keys.isEmpty()) {
                player.discoverRecipes(keys);
            }
            players++;
        }
        return players;
    }

    private static NamespacedKey keyOf(NamespacedId id) {
        return new NamespacedKey(id.namespace(), id.value());
    }
}
