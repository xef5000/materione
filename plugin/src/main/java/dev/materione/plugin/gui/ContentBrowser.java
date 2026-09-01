package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeBlock;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeFurniture;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.platform.text.ItemText;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.items.Give;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /materione browser} — the admin acquisition surface: one screen
 * per pack (grouped by namespace, which is the pack id by construction),
 * each a paginated grid of that pack's live content — items as their real
 * stacks, blocks and furniture as their placement item's real stack (the
 * pack model renders right in the grid; the typed host icon is only the
 * fallback when no placement item is live). Left-click picks one entry
 * onto the cursor, shift-click hands over a full stack. Every block and
 * furniture has a placement item now: the definition's {@code item:}
 * link, the same-id item when one exists, or the auto-generated one —
 * without any of those the click falls back to pointing at
 * {@code /materione dev place}. Synthetic placement items never appear as
 * standalone rows; their block/furniture entry represents them.
 *
 * <p>Entries snapshot from the live registries at open time; a reload
 * changes what future opens show (re-open to refresh). Tooling surface —
 * author-defined menus (M6) will render through the same gui kit rather
 * than through this class.
 */
public final class ContentBrowser {

    private final MaterionePlugin plugin;

    public ContentBrowser(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the pack picker for one viewer on their region thread. */
    public void open(Player viewer) {
        plugin.scheduling().executeEntity(plugin, viewer, () -> showPicker(viewer));
    }

    private void showPicker(Player viewer) {
        // namespace == pack id: ParseStage names every resource after its pack.
        // counts[0]=items, [1]=blocks, [2]=furniture — a pack made of only
        // blocks must still appear, so the union names the packs.
        Map<String, int[]> byPack = new LinkedHashMap<>();
        // synthetic placement items ride their block/furniture row, not the item count
        for (RuntimeItem item : registries().items()) {
            if (!item.synthetic()) {
                byPack.computeIfAbsent(item.id().namespace(), pack -> new int[3])[0]++;
            }
        }
        RuntimeBlocks blocks = registries().view(RuntimeBlocks.KEY);
        if (blocks != null) {
            for (RuntimeBlock block : blocks.all()) {
                byPack.computeIfAbsent(block.id().namespace(), pack -> new int[3])[1]++;
            }
        }
        RuntimeFurnitures furnitures = registries().view(RuntimeFurnitures.KEY);
        if (furnitures != null) {
            for (RuntimeFurniture furniture : furnitures.all()) {
                byPack.computeIfAbsent(furniture.id().namespace(), pack -> new int[3])[2]++;
            }
        }
        List<Map.Entry<String, int[]>> packs = byPack.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        PickerGui gui = new PickerGui(viewer, packs);
        viewer.openInventory(gui.getInventory());
    }

    private final class PickerGui extends PaginatedGui {

        private final Player viewer;
        private final List<Map.Entry<String, int[]>> packs;

        PickerGui(Player viewer, List<Map.Entry<String, int[]>> packs) {
            super("Materione packs", new Pagination(packs.size(), PER_PAGE));
            this.viewer = viewer;
            this.packs = packs;
            rerender();
        }

        @Override
        protected void fillContent(int page) {
            var inv = getInventory();
            for (int slot = 0, index = startIndex(page);
                    slot < PER_PAGE && index < packs.size(); slot++, index++) {
                var entry = packs.get(index);
                ItemStack icon = new ItemStack(Material.CHEST);
                icon.editMeta(meta -> {
                    meta.displayName(ItemText.nonItalic(
                            Component.text(entry.getKey(), NamedTextColor.AQUA)));
                    meta.lore(List.of(ItemText.nonItalic(Component.text(
                            summary(entry.getValue()), NamedTextColor.GRAY))));
                });
                inv.setItem(slot, icon);
            }
        }

        private String summary(int[] counts) {
            var parts = new ArrayList<String>();
            if (counts[0] > 0) {
                parts.add(counts[0] + " item(s)");
            }
            if (counts[1] > 0) {
                parts.add(counts[1] + " block(s)");
            }
            if (counts[2] > 0) {
                parts.add(counts[2] + " furniture");
            }
            return String.join(" · ", parts);
        }

        @Override
        protected void onItem(int slotInPage, boolean shift, InventoryClickEvent event) {
            int index = startIndex(page()) + slotInPage;
            if (index < packs.size()) {
                showPack(viewer, packs.get(index).getKey());
            }
        }

        @Override
        protected String infoText() {
            return "packs — page " + (page() + 1) + "/" + pages();
        }
    }

    private final class PackGui extends PaginatedGui {

        private final Player viewer;
        private final String pack;
        private final List<Entry> entries;

        PackGui(Player viewer, String pack, List<Entry> entries) {
            super("Materione — " + pack, new Pagination(entries.size(), PER_PAGE));
            this.viewer = viewer;
            this.pack = pack;
            this.entries = entries;
            rerender();
        }

        @Override
        protected void fillContent(int page) {
            var inv = getInventory();
            for (int slot = 0, index = startIndex(page);
                    slot < PER_PAGE && index < entries.size(); slot++, index++) {
                inv.setItem(slot, icon(entries.get(index)));
            }
        }

        private ItemStack icon(Entry entry) {
            if (entry instanceof ItemEntry item) {
                return MaterioneStacks.build(MaterioneStacks.specOf(item.item()));
            }
            if (entry instanceof BlockEntry block) {
                ItemStack icon = placementStack(block.placement());
                if (icon == null) {
                    icon = new ItemStack(switch (block.block().technique()) {
                        case NOTE_BLOCK -> Material.NOTE_BLOCK;
                        case STRING -> Material.STRING;
                        case CHORUS -> Material.CHORUS_PLANT;
                        case STAIRS -> Material.WAXED_CUT_COPPER_STAIRS;
                        case SLAB -> Material.WAXED_CUT_COPPER_SLAB;
                        case TECHNICAL -> Material.CHEST;
                    });
                }
                icon.editMeta(meta -> {
                    meta.displayName(ItemText.nonItalic(Component.text(
                            humanize(block.block().id().value()), NamedTextColor.AQUA)));
                    meta.lore(List.of(
                            ItemText.nonItalic(Component.text(
                                    "custom block · host " + block.block().host(),
                                    NamedTextColor.GRAY)),
                            placementLore(block.placement(), block.block().id())));
                });
                return icon;
            }
            FurnitureEntry furniture = (FurnitureEntry) entry;
            ItemStack icon = placementStack(furniture.placement());
            if (icon == null) {
                icon = new ItemStack(Material.ARMOR_STAND);
            }
            icon.editMeta(meta -> {
                meta.displayName(ItemText.nonItalic(Component.text(
                        humanize(furniture.furniture().id().value()), NamedTextColor.AQUA)));
                meta.lore(List.of(
                        ItemText.nonItalic(Component.text(
                                "furniture · " + furniture.furniture().hitboxes().size()
                                        + " hitbox(es) · " + furniture.furniture().seats().size()
                                        + " seat(s)",
                                NamedTextColor.GRAY)),
                        placementLore(furniture.placement(), furniture.furniture().id())));
            });
            return icon;
        }

        /** The placement item's real stack — the pack model renders in the grid. */
        private ItemStack placementStack(Optional<NamespacedId> placement) {
            return placement.flatMap(id -> registries().item(id))
                    .map(item -> MaterioneStacks.build(MaterioneStacks.specOf(item)))
                    .orElse(null);
        }

        private Component placementLore(Optional<NamespacedId> placement, NamespacedId id) {
            return ItemText.nonItalic(Component.text(
                    placement.map(item -> "item: " + item)
                            .orElseGet(() -> "no placement item — /materione dev place " + id),
                    NamedTextColor.GRAY));
        }

        @Override
        protected void onItem(int slotInPage, boolean shift, InventoryClickEvent event) {
            int index = startIndex(page()) + slotInPage;
            if (index >= entries.size()) {
                return;
            }
            Entry entry = entries.get(index);
            if (entry instanceof ItemEntry item) {
                give(item.item(), shift, event, null);
                return;
            }
            NamespacedId placed;
            Optional<NamespacedId> placement;
            if (entry instanceof BlockEntry block) {
                placed = block.block().id();
                placement = block.placement();
            } else {
                FurnitureEntry furniture = (FurnitureEntry) entry;
                placed = furniture.furniture().id();
                placement = furniture.placement();
            }
            if (placement.isEmpty()) {
                viewer.sendMessage(Component.text(
                        placed + " has no placement item — place it with:", NamedTextColor.RED));
                viewer.sendMessage(Component.text(
                        "/materione dev place " + placed, NamedTextColor.YELLOW));
                return;
            }
            RuntimeItem item = registries().item(placement.get()).orElse(null);
            if (item == null) {
                viewer.sendMessage(Component.text(
                        "placement item " + placement.get() + " is not live",
                        NamedTextColor.RED));
                return;
            }
            give(item, shift, event, placed);
        }

        /** The shared give flow; {@code places} annotates block/furniture hand-outs. */
        private void give(RuntimeItem item, boolean shift, InventoryClickEvent event,
                NamespacedId places) {
            if (shift) {
                ItemStack template = MaterioneStacks.build(MaterioneStacks.specOf(item));
                int amount = template.getMaxStackSize();
                Give.deliver(viewer, template, amount);
                confirm(viewer, item.id(), amount, places);
            } else if (event.getView().getCursor() == null
                    || event.getView().getCursor().getType().isAir()) {
                event.getView().setCursor(MaterioneStacks.build(MaterioneStacks.specOf(item)));
                confirm(viewer, item.id(), 1, places);
            }
        }

        @Override
        protected boolean hasBack() {
            return true;
        }

        @Override
        protected void back() {
            showPicker(viewer);
        }

        @Override
        protected String infoText() {
            return pageCountSuffix().isEmpty() ? pack : pack + pageCountSuffix();
        }

        private String pageCountSuffix() {
            return pages() == 1 ? "" : " — page " + (page() + 1) + "/" + pages();
        }
    }

    /** One browsable row: a give-able item, a custom block, or a furniture. */
    sealed interface Entry permits ItemEntry, BlockEntry, FurnitureEntry {}

    record ItemEntry(RuntimeItem item) implements Entry {}

    record BlockEntry(RuntimeBlock block, Optional<NamespacedId> placement) implements Entry {}

    record FurnitureEntry(RuntimeFurniture furniture, Optional<NamespacedId> placement)
            implements Entry {}

    /**
     * One pack's browsable rows: items first, then blocks, then furniture,
     * each group id-sorted; placement links resolved from the same
     * snapshot. Synthetic placement items are skipped — their block or
     * furniture row hands them out. Package-private static for the unit
     * test.
     */
    static List<Entry> entriesFor(
            String pack,
            Collection<RuntimeItem> items,
            RuntimeBlocks blocks,
            RuntimeFurnitures furnitures) {
        List<Entry> out = new ArrayList<>();
        items.stream()
                .filter(i -> !i.synthetic() && i.id().namespace().equals(pack))
                .sorted(Comparator.comparing(i -> i.id().toString()))
                .map(ItemEntry::new)
                .forEach(out::add);
        if (blocks != null) {
            blocks.all().stream()
                    .filter(b -> b.id().namespace().equals(pack))
                    .sorted(Comparator.comparing(b -> b.id().toString()))
                    .forEach(b -> out.add(new BlockEntry(b, blocks.itemFor(b.id()))));
        }
        if (furnitures != null) {
            furnitures.all().stream()
                    .filter(f -> f.id().namespace().equals(pack))
                    .sorted(Comparator.comparing(f -> f.id().toString()))
                    .forEach(f -> out.add(new FurnitureEntry(f, furnitures.itemFor(f.id()))));
        }
        return out;
    }

    private void showPack(Player viewer, String pack) {
        PackGui gui = new PackGui(viewer, pack, entriesFor(
                pack,
                registries().items(),
                registries().view(RuntimeBlocks.KEY),
                registries().view(RuntimeFurnitures.KEY)));
        viewer.openInventory(gui.getInventory());
    }

    private RuntimeRegistries registries() {
        return plugin.pipeline().runtime().registries();
    }

    private static void confirm(Player player, NamespacedId id, int amount, NamespacedId places) {
        Component message = Component.text("Given ", NamedTextColor.GREEN)
                .append(Component.text(id.toString(), NamedTextColor.AQUA))
                .append(Component.text(" ×" + amount, NamedTextColor.GREEN));
        if (places != null) {
            message = message.append(Component.text(" — places " + places, NamedTextColor.GRAY));
        }
        player.sendMessage(message);
    }

    /** {@code amethyst_lamp} → {@code Amethyst Lamp} (icon titles for blocks/furniture). */
    private static String humanize(String value) {
        var out = new StringBuilder();
        for (String word : value.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
