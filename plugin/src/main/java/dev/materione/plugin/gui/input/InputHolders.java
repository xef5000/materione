package dev.materione.plugin.gui.input;

import dev.materione.api.custody.CustodyHolder;
import dev.materione.api.custody.CustodyKey;
import dev.materione.plugin.escrow.runtime.CustodyAccount;
import dev.materione.plugin.escrow.runtime.Delta;
import dev.materione.plugin.escrow.runtime.EscrowRuntime;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiContexts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Resolves authored slot holder policy to live PDC accounts and stable holder groups. */
final class InputHolders {

    private final EscrowRuntime runtime;

    InputHolders(EscrowRuntime runtime) {
        this.runtime = runtime;
    }

    Optional<Resolved> resolve(Player player, GuiMenu menu, int slot) {
        CustodyHolder holder = holderOf(player, menu, slot);
        if (runtime == null || holder == null || !runtime.capability().supported()) {
            return Optional.empty();
        }
        return runtime.account(player, holder).map(account -> new Resolved(holder, account));
    }

    CustodyHolder holderOf(Player player, GuiMenu menu, int slot) {
        InputSlots.Holder spec = menu.inputHolders().getOrDefault(slot, InputSlots.Holder.PLAYER);
        if (spec == null || spec.broken()) {
            return null;
        }
        try {
            return switch (spec.kind()) {
                case PLAYER -> new CustodyHolder.Player(player.getUniqueId());
                case KEYED -> {
                    String key = spec.key() == null ? "" : spec.key().render(
                            GuiContexts.build(Viewer.of(player), menu.record(), Map.of()));
                    yield new CustodyHolder.Keyed(player.getUniqueId(), CustodyKey.require(key));
                }
                case STACK -> menu.record().stack()
                        .map(stack -> new CustodyHolder.Stack(player.getUniqueId(), stack))
                        .orElse(null);
            };
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    Map<CustodyAccount, List<Integer>> strictGroups(Player player, GuiMenu menu, Delta<ItemStack> delta,
            java.util.Set<Integer> escrowed) {
        Map<CustodyAccount, List<Integer>> groups = new HashMap<>();
        for (int slot : delta.tops().keySet()) {
            if (!escrowed.contains(slot)) {
                continue;
            }
            resolve(player, menu, slot).ifPresent(found ->
                    groups.computeIfAbsent(found.account(), ignored -> new java.util.ArrayList<>()).add(slot));
        }
        return groups;
    }

    Map<CustodyHolder, List<Integer>> slotsByHolder(
            Player player, GuiMenu menu, java.util.Collection<Integer> slots) {
        Map<CustodyHolder, List<Integer>> out = new HashMap<>();
        for (int slot : slots) {
            resolve(player, menu, slot).ifPresent(found ->
                    out.computeIfAbsent(found.holder(), ignored -> new java.util.ArrayList<>()).add(slot));
        }
        return out;
    }

    List<Integer> playerSlots(Player player, GuiMenu menu) {
        return menu.durableSlots().stream()
                .filter(slot -> holderOf(player, menu, slot) instanceof CustodyHolder.Player)
                .toList();
    }

    record Resolved(CustodyHolder holder, CustodyAccount account) {
        boolean open() {
            return account.open();
        }
    }
}
