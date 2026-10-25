package dev.materione.platform.items;

import dev.materione.api.mechanics.ItemModifier;
import org.bukkit.inventory.ItemStack;

/** Version-sensitive food component edits shared by every mechanic stack factory. */
public record FoodItemModifier(int nutrition, float saturation, boolean canAlwaysEat)
        implements ItemModifier {

    public FoodItemModifier {
        if (nutrition < 0 || nutrition > 20
                || !Float.isFinite(saturation) || saturation < 0 || saturation > 40) {
            throw new IllegalArgumentException("food requires nutrition 0..20 and finite saturation 0..40");
        }
    }

    @Override
    public void apply(ItemStack stack) {
        stack.editMeta(meta -> {
            var food = meta.getFood();
            food.setNutrition(nutrition);
            food.setSaturation(saturation);
            food.setCanAlwaysEat(canAlwaysEat);
            meta.setFood(food);
        });
    }
}
