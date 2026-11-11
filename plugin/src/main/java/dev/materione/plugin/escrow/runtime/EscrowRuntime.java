package dev.materione.plugin.escrow.runtime;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.escrow.gate.CrashGate;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.io.SnapshotReader;
import dev.materione.plugin.escrow.model.BarrierBook;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.Recovery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The player-level half of the crash-safe input runtime (gui-input-durability.md §5.5 to §5.8; row P of #247): the
 * capability, one {@link PlayerEscrow} per online player, the recovery gate that opens on join, the legacy
 * {@code gui_inputs} migration, the death release, {@code /materione claim}, and the refusal copy. The click rules and
 * the window moves are in {@code StrictInputs} (the GUI side); both call the one {@link Tier1} API.
 *
 * <p>Owner-thread rules: {@link #join}, {@link #death}, {@link #claim} and every {@link Tier1} step run on the
 * player's own thread (the region's, on Folia; one {@code executeEntity} task per step). The ledger is read from memory
 * ({@link EscrowLedgerStore#preloaded}); when its pre-login preload has not finished, the gate stays closed and the
 * recovery is retried through the scheduling gateway when the preload completes: never a blocking wait (I4).
 */
public final class EscrowRuntime {

    /** The persistent-data key of the account. */
    public static final String ACCOUNT_KEY = "gui_escrow";

    /** The persistent-data key of the legacy journal ({@code GuiJournal}, §10). */
    public static final String LEGACY_KEY = "gui_inputs";

    private static final long REPORT_EVERY_MILLIS = 60_000L;

    /** The window a player has open, as the GUI side sees it. */
    public interface WindowView {

        /** The player's open Materione window and its escrowed slots, when there is one. */
        Optional<Open> open(Player player);

        /** Nothing is open. */
        WindowView NONE = player -> Optional.empty();

        /**
         * @param gui      the GUI's id, for the entries' origin
         * @param top      the window's own inventory
         * @param escrowed the window slots of its {@code durable: required} layers
         */
        record Open(String gui, Inventory top, Set<Integer> escrowed) {}
    }

    /** How a player's strict slots stand (§5.7, §5.8). */
    public enum Standing {
        /** Strict transfers are served. */
        SERVED,
        /** The platform lacks checkpoint support: required layers are refused ({@code MTR-0100}). */
        UNSUPPORTED,
        /** The recovery gate has not opened yet (the preload is still running). */
        LOADING,
        /** The player's escrow state is unreadable ({@code MTR-0101}). */
        BLOCKED
    }

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final EscrowLedgerStore ledger;
    private final EscrowMessages messages;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final Supplier<Collection<? extends Player>> online;
    private final NamespacedKey accountKey;
    private final NamespacedKey legacyKey;
    private final Tier1<ItemStack> tier1;
    private final SaveBarrier barrier;
    private final ExitRunner<ItemStack> exits;
    private final ConcurrentMap<UUID, PlayerEscrow> players = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> reported = new ConcurrentHashMap<>();
    private volatile WindowView windows = WindowView.NONE;
    private volatile boolean platformKnown;
    private volatile boolean savingDisabled;
    private volatile Optional<String> directoryProbe = Optional.empty();
    private volatile boolean probeFinished;
    private volatile String publishedKey = "";
    private volatile Runnable onChecked = () -> { };

    /**
     * @param ledger the ledger store; null when the {@code escrow-ledger} phase degraded (the capability then fails)
     * @param online the online players, for the enable and disable passes
     * @param snapshots the read-back of a player's saved file (tier 3); null with {@code ledger}
     * @param barrierCooldownMillis the minimum time between two barrier saves of one player ({@code escrow_barrier_cooldown_seconds})
     */
    public EscrowRuntime(Plugin plugin, SchedulingGateway scheduling, EscrowLedgerStore ledger,
            EscrowMessages messages, Consumer<Diagnostic> diagnostics, LongSupplier clock,
            Supplier<Collection<? extends Player>> online, SnapshotReader snapshots, long barrierCooldownMillis) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.ledger = ledger;
        this.messages = messages;
        this.diagnostics = diagnostics;
        this.clock = clock;
        this.online = online;
        this.accountKey = new NamespacedKey(plugin, ACCOUNT_KEY);
        this.legacyKey = new NamespacedKey(plugin, LEGACY_KEY);
        this.tier1 = new Tier1<>(ItemStacks.INSTANCE, EscrowRuntime::randomId, clock);
        if (ledger != null && snapshots != null) {
            this.barrier = new SaveBarrier(ledger, snapshots, clock, barrierCooldownMillis,
                    BarrierBook.DEFAULT_GIVE_UP_MILLIS, plugin.getLogger()::warning);
            this.exits = new ExitRunner<>(tier1, ledger, barrier);
        } else {
            this.barrier = null;
            this.exits = null;
        }
    }

    private static Id128 randomId() {
        UUID random = UUID.randomUUID();
        return new Id128(random.getMostSignificantBits(), random.getLeastSignificantBits() | 1L);
    }

    /** The one tier-1 API. */
    public Tier1<ItemStack> tier1() {
        return tier1;
    }

    /**
     * Tier 2, steps 2 to 4 (gui-input-durability.md §5.4; row B): the entry point #248's consume action calls after
     * {@link Tier1#beginExit} or {@link Tier1#freezeSlot}. Empty when the ledger did not start (the capability fails
     * and a required layer is refused, so no exit begins).
     */
    public Optional<ExitRunner<ItemStack>> exits() {
        return Optional.ofNullable(exits);
    }

    /** The player as the tier-2 and tier-3 runtime needs them: their own thread, live state and save. */
    public Owner<ItemStack> owner(Player player) {
        return new PlayerOwner(plugin, scheduling, player, of(player),
                () -> windows.open(player).map(WindowView.Open::top).orElse(null));
    }

    /** Where the runtime finds a player's open window (bound once the GUI stack exists). */
    public void bindWindows(WindowView view) {
        this.windows = view;
    }

    public EscrowMessages messages() {
        return messages;
    }

    // ---- capability (§5.8) ------------------------------------------------------------------------------------

    /**
     * Computes the capability: the platform and settings facts now, and the directory probe on the IO executor. Called
     * at enable and again on every reload.
     *
     * @param directoryProbe the file probe of {@code storage/escrow/} (a create-and-delete; empty when writable, else
     *     the cause), run on the IO executor; null when storage did not start. It lives in the IO half
     *     ({@code DirectoryProbe}) because this half may not touch a file
     */
    public void check(boolean platformKnown, boolean savingDisabled, Supplier<Optional<String>> directoryProbe) {
        this.platformKnown = platformKnown;
        this.savingDisabled = savingDisabled;
        if (directoryProbe == null || ledger == null) {
            this.directoryProbe = Optional.empty();
            this.probeFinished = true;
            return;
        }
        // a re-check keeps the last result until the new probe lands: a reload must not blink every required slot off
        scheduling.executeAsync(plugin, () -> {
            this.directoryProbe = directoryProbe.get();
            this.probeFinished = true;
            onChecked.run();
        });
    }

    /** What to do when the off-thread directory probe finishes (the plugin publishes {@code MTR-0100} then). */
    public void onChecked(Runnable callback) {
        this.onChecked = callback;
    }

    /** The capability as it stands now. */
    public EscrowCapability capability() {
        return EscrowCapability.evaluate(new EscrowCapability.Facts(
                platformKnown, savingDisabled, ledger != null, directoryProbe, probeFinished));
    }

    /**
     * Publishes {@code MTR-0100} for a failed check (error while a {@code durable: required} layer is live, warning
     * otherwise), once per change of the failed check or the severity. A supported platform clears the memory, so a
     * later regression is reported again.
     */
    public void publishCapability(boolean requiredLayerLive) {
        EscrowCapability capability = capability();
        if (capability.supported() || capability.failed() == EscrowCapability.Check.PROBING) {
            if (capability.supported()) {
                publishedKey = "";
            }
            return;
        }
        Severity severity = requiredLayerLive ? Severity.ERROR : Severity.WARN;
        String key = capability.failed() + "/" + severity;
        if (key.equals(publishedKey)) {
            return;
        }
        publishedKey = key;
        diagnostics.accept(new Diagnostic(DiagnosticCodes.ESCROW_UNSUPPORTED, severity,
                capability.describe(), null, null, capability.detail()));
    }

    // ---- the gate (§5.7) --------------------------------------------------------------------------------------

    /** The player's escrow, created closed on first use. */
    public PlayerEscrow of(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), id -> create(player));
    }

    private PlayerEscrow create(Player player) {
        return new PlayerEscrow(bytes -> player.getPersistentDataContainer().set(accountKey,
                PersistentDataType.BYTE_ARRAY, bytes));
    }

    /** How the player's strict slots stand: served, or why they are refused. */
    public Standing standing(Player player) {
        if (!capability().supported()) {
            return Standing.UNSUPPORTED;
        }
        PlayerEscrow escrow = of(player);
        return switch (escrow.gate()) {
            case OPEN -> Standing.SERVED;
            case CLOSED -> Standing.LOADING;
            case UNRECOVERABLE -> Standing.BLOCKED;
        };
    }

    /**
     * The player joined: the gate closes, then recovery runs (§5.7). Runs on the player's own thread, at the first
     * priority, so no interaction can precede it.
     */
    public void join(Player player) {
        PlayerEscrow escrow = create(player);
        players.put(player.getUniqueId(), escrow);
        recover(player, escrow);
    }

    /** The player left: their live state is dropped (the saved account is what counts). */
    public void quit(Player player) {
        if (barrier != null) {
            barrier.left(player.getUniqueId()); // effects waiting on the barrier are abandoned; the quit save is polled
        }
        players.remove(player.getUniqueId());
    }

    /** A player already online when the plugin enables or reloads: the same recovery, on their own thread. */
    public void adoptOnline() {
        for (Player player : List.copyOf(online.get())) {
            scheduling.executeEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    join(player);
                }
            });
        }
    }

    private void recover(Player player, PlayerEscrow escrow) {
        if (ledger == null) {
            return; // nothing to read the decisions from: the gate cannot open (MTR-0100 names it)
        }
        UUID id = player.getUniqueId();
        Optional<LedgerState> state = ledger.preloaded(id);
        if (state.isEmpty()) {
            tell(player, messages.loading());
            ledger.preload(id).thenAccept(ready -> scheduling.executeEntity(plugin, player, () -> {
                if (player.isOnline() && players.get(id) == escrow && escrow.gate() == PlayerEscrow.Gate.CLOSED) {
                    recover(player, escrow);
                }
            }));
            return;
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        AccountRead saved = AccountCodec.read(data.get(accountKey, PersistentDataType.BYTE_ARRAY));
        byte[] legacyBytes = data.get(legacyKey, PersistentDataType.BYTE_ARRAY);
        List<ItemStack> legacy = legacyBytes == null ? List.of() : decodeLegacy(legacyBytes);
        Tier1.Recovered result = tier1.recover(escrow, new BukkitLive(player, null), saved, state.get(), legacy);
        switch (result) {
            case Tier1.Opened opened -> {
                if (legacyBytes != null && !legacy.isEmpty() && !opened.legacyKept()) {
                    data.remove(legacyKey); // adopted as OWED entries: the old journal lets go (§10)
                }
                announce(player, opened);
            }
            case Tier1.Blocked blocked -> {
                if (blocked.cause() == Recovery.Cause.ACCOUNT) {
                    report(id, "account", new Diagnostic(DiagnosticCodes.ESCROW_STATE_UNREADABLE, Severity.WARN,
                            "the escrow account of " + player.getName() + " is unreadable (" + blocked.detail()
                                    + "); it is preserved untouched and their strict input transfers are refused",
                            null, null, "an admin resolves it by hand: the value is materione:gui_escrow in the player's data"));
                }
                tell(player, messages.blocked());
            }
        }
    }

    private List<ItemStack> decodeLegacy(byte[] bytes) {
        try {
            var out = new ArrayList<ItemStack>();
            for (ItemStack item : ItemStack.deserializeItemsFromBytes(bytes)) {
                if (item != null && !item.getType().isAir()) {
                    out.add(item);
                }
            }
            return out;
        } catch (RuntimeException unreadable) {
            plugin.getLogger().warning("MATERIONE-ESCROW the legacy gui_inputs journal could not be read ("
                    + unreadable + "); it is left in place");
            return List.of();
        }
    }

    private void announce(Player player, Tier1.Opened opened) {
        if (opened.owed() > 0) {
            report(player.getUniqueId(), "owed", new Diagnostic(DiagnosticCodes.ESCROW_RECOVERY_OWED, Severity.WARN,
                    "recovery left " + opened.owed() + " stack(s) owed to " + player.getName()
                            + " because their inventory is full",
                    null, null, "the player collects them with /materione claim"));
            player.sendMessage(Component.text(EscrowMessages.count(messages.recoveredOwed(), opened.owed())));
        } else if (opened.returned() > 0 || opened.adopted() > 0) {
            player.sendMessage(Component.text(
                    EscrowMessages.count(messages.recovered(), Math.max(opened.returned(), opened.adopted()))));
        }
    }

    // ---- death (§5.5, owner answer 10) ------------------------------------------------------------------------

    /**
     * The player died: the HELD entries are released inside the event, before the drop list is fixed: into the
     * inventory with {@code keepInventory}, otherwise appended to {@code event.getDrops()}. The window's escrowed slots
     * are cleared in the same step, so the close the server does right after returns nothing a second time.
     */
    public void death(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerEscrow escrow = players.get(player.getUniqueId());
        if (escrow == null || !escrow.open()) {
            return;
        }
        Optional<WindowView.Open> window = windows.open(player);
        Ledger decisions = ledger == null ? Ledger.EMPTY : ledger.preloaded(player.getUniqueId())
                .filter(LedgerState.Ready.class::isInstance)
                .map(state -> ((LedgerState.Ready) state).ledger())
                .orElse(Ledger.EMPTY);
        Tier1.Death<ItemStack> death = tier1.death(escrow,
                new BukkitLive(player, window.map(WindowView.Open::top).orElse(null)),
                window.map(WindowView.Open::gui).orElse("death"),
                window.map(WindowView.Open::escrowed).orElse(Set.of()), decisions, event.getKeepInventory());
        event.getDrops().addAll(death.drops());
        CrashGate.at(CrashGate.Gates.DEATH_RELEASED); // test-only, inert in production
        if (death.owed() > 0) {
            player.sendMessage(Component.text(EscrowMessages.count(messages.owed(), death.owed())));
        }
    }

    /** A respawned player's data: writes the live account into it again, so owed stacks outlive the respawn. */
    public void respawned(Player player) {
        PlayerEscrow escrow = players.get(player.getUniqueId());
        if (escrow != null && escrow.open() && !escrow.account().isEmpty()) {
            escrow.persistAgain(AccountCodec.encode(escrow.account()));
        }
    }

    // ---- claim (§5.5) -----------------------------------------------------------------------------------------

    /** {@code /materione claim}: tells the player what came back and what is still owed. Owner thread. */
    public void claim(Player player) {
        claimNow(player);
        CrashGate.at(CrashGate.Gates.CLAIM_DONE); // test-only, inert in production
    }

    private void claimNow(Player player) {
        if (standing(player) != Standing.SERVED) {
            refuse(player, Tier1.Reason.UNAVAILABLE);
            return;
        }
        PlayerEscrow escrow = of(player);
        if (Tier1.countOwed(escrow.account()) == 0) {
            player.sendMessage(Component.text(messages.claimNothing()));
            return;
        }
        Tier1.Result<ItemStack> result = tier1.claim(escrow, new BukkitLive(player, null));
        if (result instanceof Tier1.Done<ItemStack> done) {
            player.sendMessage(Component.text(done.returned() > 0
                    ? EscrowMessages.count(messages.claimed(), done.returned())
                    : EscrowMessages.count(messages.claimStillOwed(), done.owed())));
            if (done.returned() > 0 && done.owed() > 0) {
                player.sendMessage(Component.text(EscrowMessages.count(messages.claimStillOwed(), done.owed())));
            }
        } else {
            refuse(player, ((Tier1.Denied<ItemStack>) result).reason());
        }
    }

    // ---- refusal UX (§7) --------------------------------------------------------------------------------------

    /** The player's strict click did nothing: one actionbar line, and the reason logged with its code once a minute. */
    public void refuse(Player player, Tier1.Reason reason) {
        Standing standing = standing(player);
        String line = switch (reason) {
            case BUSY -> messages.busy();
            default -> switch (standing) {
                case LOADING -> messages.loading();
                case BLOCKED -> messages.blocked();
                default -> messages.unavailable();
            };
        };
        player.sendActionBar(Component.text(line));
        UUID id = player.getUniqueId();
        if (reason == Tier1.Reason.UNRECORDABLE) {
            report(id, "unrecordable", new Diagnostic(DiagnosticCodes.ESCROW_TRANSFER_REFUSED, Severity.WARN,
                    "a strict input transfer for " + player.getName() + " could not be recorded (the account is over "
                            + "its cap or a stack does not serialise); the click did nothing",
                    null, null, "claim owed stacks with /materione claim, or empty some input slots"));
        } else if (reason == Tier1.Reason.UNAVAILABLE && standing == Standing.UNSUPPORTED && due(id, "unsupported")) {
            plugin.getLogger().info(DiagnosticCodes.ESCROW_UNSUPPORTED.code() + ": " + player.getName()
                    + " clicked a durable: required slot; refused (" + capability().failed().label() + ")");
        }
    }

    /** The owed-stack line a close adds (§5.5). */
    public void owed(Player player, int owed) {
        if (owed > 0) {
            player.sendActionBar(Component.text(EscrowMessages.count(messages.owed(), owed)));
        }
    }

    private void tell(Player player, String line) {
        player.sendMessage(Component.text(line));
    }

    private void report(UUID player, String what, Diagnostic diagnostic) {
        if (due(player, what + "/" + diagnostic.code().code())) {
            diagnostics.accept(diagnostic);
        }
    }

    /** True at most once per key per minute. */
    private boolean due(UUID player, String key) {
        return due(player + "/" + key);
    }

    private boolean due(String key) {
        long now = clock.getAsLong();
        boolean[] fire = new boolean[1];
        reported.compute(key, (k, last) -> {
            if (last == null || now - last >= REPORT_EVERY_MILLIS) {
                fire[0] = true;
                return now;
            }
            return last;
        });
        return fire[0];
    }

    // ---- disable ----------------------------------------------------------------------------------------------

    /** Plugin disable, after {@link #releaseAll}: every exit waiting on the barrier is abandoned (its effect never runs). */
    public void stop() {
        if (barrier != null) {
            barrier.close();
        }
    }

    /**
     * Plugin disable or {@code /reload} (K20): every online player's open window is released in one step before the
     * server saves, so the snapshot holds the stacks in the inventory or OWED, never HELD in a window that is about to
     * stop being managed. Best effort per player: a player this thread does not own (Folia) is left to recovery.
     */
    public void releaseAll() {
        for (Player player : List.copyOf(online.get())) {
            PlayerEscrow escrow = players.get(player.getUniqueId());
            if (escrow == null || !escrow.open()) {
                continue;
            }
            try {
                Optional<WindowView.Open> window = windows.open(player);
                if (window.isPresent()) {
                    WindowView.Open open = window.get();
                    tier1.release(escrow, new BukkitLive(player, open.top()), open.gui(), open.escrowed());
                }
            } catch (RuntimeException skipped) {
                // not on this player's thread, or a half-built window: recovery returns the stacks at the next join
            }
        }
    }
}
