package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The owner-thread budget of the escrow (plugin.md section 9, gui-input-durability.md section 8): encoding a 27-stack
 * account takes at most 200 microseconds, and one strict click, the whole tier-1 step on a 27-entry account (plan,
 * eager encode, write), stays well under a tick's share. The thread does memory only: nothing here opens a file (the
 * ban tests hold that). Medians over many runs after a warm-up, so one GC pause does not decide it.
 */
class EscrowPerfTest {

    /** A realistic stack: about the size of a serialised enchanted, named item. */
    private static StackBytes stack(int n) {
        byte[] bytes = new byte[160];
        Arrays.fill(bytes, (byte) n);
        return StackBytes.of(bytes);
    }

    private static Account account(int entries) {
        List<Entry> list = new ArrayList<>();
        for (int i = 1; i <= entries; i++) {
            list.add(Entry.held(new Id128(1, i), stack(i), "shop:vault", i % 27, 1_000L + i));
        }
        return new Account(1, list);
    }

    private static long medianNanos(Runnable work, int runs) {
        for (int i = 0; i < runs; i++) {
            work.run(); // warm-up
        }
        long[] times = new long[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            work.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[runs / 2];
    }

    @Test
    void encodingA27StackAccountIsUnder200Microseconds() {
        Account account = account(27);
        long median = medianNanos(() -> AccountCodec.encode(account), 2_000);
        System.out.println("MATERIONE_ESCROW_PERF encode27=" + median / 1_000 + "us");
        assertThat(median).as("27-stack account encode, median ns").isLessThan(200_000L);
    }

    @Test
    void aStrictClickOnA27EntryAccountIsOneCheapStep() {
        Rig rig = Rig.open();
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            list.add(Entry.held(new Id128(1, i + 1), rig.stacks.encode(item("gem" + i, 64)), GUI, 9 + i, 1L));
            rig.world.top(9 + i, item("gem" + i, 64));
        }
        rig.escrow.open(new Account(1, list));
        int[] toggle = {0};
        Runnable click = () -> {
            // place and pick back one item on slot 0: a plan, an eager encode of a 27-entry account, one write
            Item put = toggle[0]++ % 2 == 0 ? item("coal", 3) : null;
            rig.tier1.apply(rig.escrow, rig.world, GUI, slot -> slot < 9, new Delta<Item>().top(0, put));
            if (rig.written.size() > 8) {
                rig.written.clear(); // the rig keeps every write; the real value is one array replaced in place
            }
        };
        long median = medianNanos(click, 2_000);
        System.out.println("MATERIONE_ESCROW_PERF click27=" + median / 1_000 + "us");
        assertThat(median).as("one strict click on a 27-entry account, median ns").isLessThan(1_000_000L);
    }

    @Test
    void aNineSlotShiftIsOneStepUnderTwoMilliseconds() {
        Rig rig = Rig.open();
        long median = medianNanos(() -> {
            Delta<Item> delta = new Delta<>();
            for (int slot = 0; slot < 9; slot++) {
                delta.top(slot, rig.world.top(slot) == null ? item("gem", 64) : null);
            }
            rig.tier1.apply(rig.escrow, rig.world, GUI, slot -> slot < 9, delta);
            rig.written.clear();
        }, 1_000);
        System.out.println("MATERIONE_ESCROW_PERF shift9=" + median / 1_000 + "us");
        assertThat(median).as("a shift over nine escrowed slots, median ns").isLessThan(2_000_000L);
    }
}
