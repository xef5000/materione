package dev.materione.platform.items;

import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;

/** The running server's supported, non-legacy vanilla item materials. */
public final class ItemMaterialPalette {

    private ItemMaterialPalette() {}

    public static List<String> supportedItemMaterials() {
        return Arrays.stream(Material.values())
                .filter(material -> material.isItem() && !material.isAir() && !material.isLegacy())
                .map(Material::name)
                .sorted()
                .toList();
    }
}
