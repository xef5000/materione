package dev.materione.harness.probe;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Test-only Folia probe for gui-input-durability.md section 11, P1 / F12 (row R2, #306). It never ships and it runs only
 * on a Folia server (it declares {@code folia-supported}); the player it works on is a real network client (the harness
 * runs {@code harness/protocol/idle-bot.mjs}).
 *
 * <p>The question F12 asks: can the player's save (PlayerDataStorage.save) interleave with a plugin step that runs on the
 * player's entity scheduler? The probe answers it by experiment, with a positive control so a "no interleaving" result is
 * not just a detector that cannot see any:
 *
 * <pre>
 *   fprobe thread &lt;player&gt;     run one entity-scheduler task: log the thread, whether the region owns the player, and
 *                              call Player#saveData from it (a save on the owner thread)
 *   fprobe straddle &lt;player&gt;   every tick, on the entity scheduler: write persistent data a=n, hold the thread 20 ms,
 *                              write b=n. A save that interleaves with the step can capture a != b. The harness reads the
 *                              player's .dat in a loop while the Paper autosave (rate 1) saves the player
 *   fprobe control &lt;player&gt; &lt;n&gt;  positive control: a plain Java thread calls Player#saveData n times, off the owner thread,
 *                              logging how many saves ran and the first failure, if any
 *   fprobe stop                 cancel the straddle task
 * </pre>
 *
 * Every answer is one log line containing {@code MATERIONE-FPROBE}.
 */
public final class FoliaProbe extends JavaPlugin {

    private static final String PREFIX = "MATERIONE-FPROBE ";

    private NamespacedKey a;
    private NamespacedKey b;
    private volatile ScheduledTask straddle;
    private final AtomicLong counter = new AtomicLong();

    @Override
    public void onEnable() {
        a = new NamespacedKey(this, "a");
        b = new NamespacedKey(this, "b");
        getLogger().info(PREFIX + "ready");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (args.length == 0 ? "" : args[0]) {
                case "thread" -> thread(player(args[1]));
                case "straddle" -> straddle(player(args[1]));
                case "control" -> control(player(args[1]), Integer.parseInt(args[2]));
                case "stop" -> {
                    if (straddle != null) {
                        straddle.cancel();
                    }
                    getLogger().info(PREFIX + "stop ok=true steps=" + counter.get());
                }
                default -> throw new IllegalArgumentException("unknown stage");
            }
        } catch (Throwable failure) {
            getLogger().severe(PREFIX + (args.length == 0 ? "?" : args[0]) + " ok=false error=" + failure);
        }
        return true;
    }

    private Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalStateException(name + " is not online");
        }
        return player;
    }

    private void thread(Player player) {
        player.getScheduler().run(this, task -> {
            boolean owned = Bukkit.isOwnedByCurrentRegion(player);
            long started = System.nanoTime();
            player.saveData();
            getLogger().info(PREFIX + "thread ok=true thread=" + Thread.currentThread().getName() + " owned=" + owned
                    + " saveMicros=" + (System.nanoTime() - started) / 1000);
        }, () -> getLogger().info(PREFIX + "thread ok=false retired=true"));
    }

    private void straddle(Player player) {
        AtomicBoolean named = new AtomicBoolean();
        straddle = player.getScheduler().runAtFixedRate(this, task -> {
            if (named.compareAndSet(false, true)) {
                getLogger().info(PREFIX + "straddle ok=true thread=" + Thread.currentThread().getName());
            }
            long n = counter.incrementAndGet();
            player.getPersistentDataContainer().set(a, PersistentDataType.LONG, n);
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            player.getPersistentDataContainer().set(b, PersistentDataType.LONG, n);
        }, () -> getLogger().info(PREFIX + "straddle retired=true"), 1, 1);
    }

    private void control(Player player, int saves) {
        Thread thread = new Thread(() -> {
            AtomicInteger done = new AtomicInteger();
            String firstFailure = "none";
            for (int i = 0; i < saves; i++) {
                try {
                    player.saveData();
                    done.incrementAndGet();
                } catch (Throwable failure) {
                    if (firstFailure.equals("none")) {
                        firstFailure = failure.getClass().getName() + ": " + failure.getMessage();
                    }
                }
                try {
                    Thread.sleep(3);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            getLogger().info(PREFIX + "control ok=true saved=" + done.get() + " requested=" + saves
                    + " firstFailure=" + firstFailure + " thread=" + Thread.currentThread().getName());
        }, "fprobe-control");
        thread.setDaemon(true);
        thread.start();
    }
}
