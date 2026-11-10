package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.SLOTS_0_TO_8;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * The runtime's half of the crash proof (gui-input-durability.md §5.10, rows K1 to K22), over the real {@link Tier1}
 * and the real account codec: random sequences of strict clicks, closes, claims and deaths, and after <em>every</em>
 * step a kill is simulated. What a kill leaves is the inventory and the account bytes the last step wrote (the slots
 * and the cursor are not saved: F4); a fresh player recovers from exactly that, and must own, in inventory plus owed,
 * exactly what the live player owned at that instant, outside the cursor (G1, G5). Between steps nothing the player
 * owns is created or lost (the one thing that leaves is a death's drop list, counted).
 *
 * <p>It proves the tier-1 runtime against the model's own oracle; a real {@code kill -9} on a real server is row H.
 */
class Tier1CrashPropertyTest {

    private static final int SEEDS = 150;
    private static final int STEPS = 70;
    private static final Set<Integer> SLOTS = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
    private static final String[] IDS = {"gem", "coal", "sword", "bread"};

    @Test
    void everyStepKeepsTheInvariantsAndEveryKillRecoversWhatThePlayerOwned() {
        int kills = 0;
        int overflow = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Random random = new Random(seed);
            Rig rig = Rig.open();
            ClickPlanner<Item> planner = new ClickPlanner<>(rig.stacks);
            int given = 0;
            int ground = 0;
            for (int step = 0; step < STEPS; step++) {
                String what = "seed " + seed + " step " + step;
                int op = random.nextInt(12);
                switch (op) {
                    case 0 -> {
                        Item stack = item(IDS[random.nextInt(IDS.length)], 1 + random.nextInt(20));
                        int slot = random.nextInt(rig.world.bag.length - 5);
                        if (rig.world.bag[slot] == null) {
                            rig.world.bag[slot] = stack;
                            given += stack.amount();
                        }
                    }
                    case 1 -> { // lift something from the inventory to the cursor, as vanilla does
                        int slot = random.nextInt(Live.STORAGE);
                        if (rig.world.cursor == null && rig.world.bag[slot] != null) {
                            rig.world.cursor = rig.world.bag[slot];
                            rig.world.bag[slot] = null;
                        }
                    }
                    case 2 -> { // put the cursor back in the inventory
                        int slot = random.nextInt(Live.STORAGE);
                        if (rig.world.cursor != null && rig.world.bag[slot] == null) {
                            rig.world.bag[slot] = rig.world.cursor;
                            rig.world.cursor = null;
                        }
                    }
                    case 3, 4, 5, 6 -> click(rig, planner, random);
                    case 7 -> shiftIn(rig, random);
                    case 8 -> rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS);
                    case 9 -> rig.tier1.claim(rig.escrow, rig.world);
                    case 10 -> {
                        boolean keep = random.nextBoolean();
                        var death = rig.tier1.death(rig.escrow, rig.world, GUI, SLOTS, Ledger.EMPTY, keep);
                        ground += death.drops().stream().mapToInt(Item::amount).sum();
                    }
                    default -> {
                        if (random.nextInt(4) == 0) { // a full inventory: the overflow path
                            for (int i = 0; i < Live.STORAGE; i++) {
                                if (rig.world.bag[i] == null) {
                                    rig.world.bag[i] = item("dirt", 64);
                                    given += 64;
                                }
                            }
                        }
                    }
                }
                rig.assertConsistent(SLOTS);
                Map<String, Integer> owned = rig.owned();
                int totalNow = owned.values().stream().mapToInt(Integer::intValue).sum()
                        + (rig.world.cursor == null ? 0 : rig.world.cursor.amount()) + ground;
                assertThat(totalNow).as("%s: nothing created or lost", what).isEqualTo(given);

                // the kill: the inventory and the account bytes survive, the slots and the cursor do not
                Rig reborn = new Rig();
                System.arraycopy(rig.world.bag, 0, reborn.world.bag, 0, rig.world.bag.length);
                AccountRead saved = rig.written.isEmpty() ? new AccountRead.Absent()
                        : AccountCodec.decode(rig.written.get(rig.written.size() - 1));
                reborn.tier1.recover(reborn.escrow, reborn.world, saved, new LedgerState.Ready(Ledger.EMPTY), List.of());
                assertThat(reborn.escrow.open()).isTrue();
                assertThat(reborn.owned()).as("%s: a kill here recovers what was owned", what).isEqualTo(owned);
                if (Tier1.countOwed(reborn.escrow.account()) > 0) {
                    overflow++;
                }
                kills++;
            }
        }
        assertThat(kills).isEqualTo(SEEDS * STEPS);
        assertThat(overflow).as("the run really reached owed stacks after a recovery").isGreaterThan(50);
    }

    private static void click(Rig rig, ClickPlanner<Item> planner, Random random) {
        InventoryAction[] actions = {InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PLACE_ALL,
                InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR,
                InventoryAction.HOTBAR_SWAP};
        InventoryAction action = actions[random.nextInt(actions.length)];
        int slot = random.nextInt(9);
        Integer bag = action == InventoryAction.HOTBAR_SWAP ? (random.nextBoolean() ? random.nextInt(9) : Live.OFFHAND) : null;
        var outcome = planner.plan(action, rig.world.top(slot), rig.world.cursor(), bag == null ? null : rig.world.bag(bag));
        if (outcome.isEmpty()) {
            return;
        }
        Delta<Item> delta = new Delta<Item>().top(slot, outcome.get().slot()).cursor(outcome.get().cursor());
        if (bag != null) {
            delta.bag(bag, outcome.get().other());
        }
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
    }

    private static void shiftIn(Rig rig, Random random) {
        int from = random.nextInt(Live.STORAGE);
        Item source = rig.world.bag[from];
        if (source == null) {
            return;
        }
        int slot = random.nextInt(9);
        Item there = rig.world.top(slot);
        if (there != null && !there.id().equals(source.id())) {
            return;
        }
        int room = 64 - (there == null ? 0 : there.amount());
        int moved = Math.min(room, source.amount());
        if (moved <= 0 || source.id().equals("sword") && there != null) {
            return;
        }
        Map<Integer, Item> tops = new HashMap<>();
        tops.put(slot, item(source.id(), (there == null ? 0 : there.amount()) + moved));
        Delta<Item> delta = new Delta<Item>().top(slot, tops.get(slot))
                .bag(from, moved == source.amount() ? null : item(source.id(), source.amount() - moved));
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
    }
}
