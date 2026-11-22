package dev.materione.plugin.protection;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.protection.ProtectionApi;
import dev.materione.api.protection.ProtectionMode;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionQuery;
import dev.materione.api.protection.ProtectionSafety;
import dev.materione.plugin.logic.world.Regions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * The protection port's service (protection.md, logic.md §43, #466): the providers registered by adapters and addons, the
 * {@link ProtectionSettings}, and the one {@link #ask} every verb and condition goes through.
 *
 * <p><b>Combining</b> (protection.md section 4). Providers are asked in registration order and the first deny ends the
 * question; otherwise any allow is an allow, and when none has an opinion the answer is an abstain. An actor-less question
 * the {@code protection_no_actor: allow} switch lets through is an abstain without asking anyone.
 *
 * <p><b>Threads</b> (section 5). Providers read the world, so {@link #ask} runs on the thread that owns the location: it checks
 * with the {@link Regions} seam, and a provider that is not {@code ANY_THREAD} asked from a thread that does not own the
 * location is a failed call (a deny, {@code MTR-0122}), never a read from the wrong thread. {@link #askAt} hops first. An online
 * actor whose own thread is not the asking thread is handed to the providers as an offline actor.
 *
 * <p><b>Failure</b> (section 6). A provider that throws, or that links badly, is a deny for that one call, and
 * {@code MTR-0122} is reported at most once per provider per ten seconds. No answer is cached: every adapter reads an
 * in-memory index, and a cache would have to learn each plugin's claim changes to stay correct.
 *
 * <p>It is a listener only to drop the providers a disabling plugin registered; the plugin registers it once.
 */
public final class ProtectionService implements ProtectionApi, Listener, AutoCloseable {

    /** Minimum milliseconds between two reports of one provider's failure. */
    static final long REPORT_INTERVAL_MS = 10_000;

    private final ProtectionSettings settings;
    private final Regions regions;
    private final boolean folia;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final Function<UUID, OfflinePlayer> offlinePlayers;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, Long> reported = new ConcurrentHashMap<>();

    private record Entry(Plugin owner, ProtectionPort port) {}

    /**
     * @param settings the mode and the actor-less switch
     * @param regions the ownership check and the hop
     * @param folia whether providers that can only run on the main thread are refused
     * @param diagnostics where {@code MTR-0122} and {@code MTR-0124} go (may be null)
     * @param clock milliseconds, for the report rate limit
     */
    public ProtectionService(ProtectionSettings settings, Regions regions, boolean folia,
            Consumer<Diagnostic> diagnostics, LongSupplier clock) {
        this(settings, regions, folia, diagnostics, clock, Bukkit::getOfflinePlayer);
    }

    /** As above, with how an offline record is found for an actor whose thread is not the asking one (a test stands in). */
    public ProtectionService(ProtectionSettings settings, Regions regions, boolean folia,
            Consumer<Diagnostic> diagnostics, LongSupplier clock, Function<UUID, OfflinePlayer> offlinePlayers) {
        this.offlinePlayers = Objects.requireNonNull(offlinePlayers, "offlinePlayers");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.folia = folia;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.clock = clock;
    }

    /** A service in {@code event} mode with no provider: the port is never asked. What a test that wants none builds. */
    public static ProtectionService inert() {
        return new ProtectionService(ProtectionSettings.EVENT_ONLY, new Regions() {
            @Override
            public boolean ownedHere(org.bukkit.Location location) {
                return true;
            }

            @Override
            public <T> CompletionStage<T> at(org.bukkit.Location location, java.util.function.Supplier<T> task) {
                return CompletableFuture.completedFuture(task.get());
            }
        }, false, null, System::currentTimeMillis);
    }

    @Override
    public ProtectionMode mode() {
        return settings.mode();
    }

    /** The settings this service runs on. */
    public ProtectionSettings settings() {
        return settings;
    }

    /** Whether a verb asks the providers: the mode is not {@code event}. */
    public boolean asksPort() {
        return settings.mode().asksPort();
    }

    /** Whether a verb's player action also runs the event path: the mode is not {@code port}. */
    public boolean runsEventPath() {
        return settings.mode().runsEventPath();
    }

    @Override
    public List<ProviderInfo> providers() {
        List<ProviderInfo> info = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            ProtectionPort port = entry.port();
            info.add(new ProviderInfo(port.name(), entry.owner().getName(), safetyOf(port),
                    port.describe().description()));
        }
        return List.copyOf(info);
    }

    @Override
    public Registration register(Plugin owner, ProtectionPort port) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(port, "port");
        if (!owner.isEnabled()) {
            throw new IllegalStateException("plugin " + owner.getName() + " is not enabled");
        }
        if (folia && safetyOf(port) == ProtectionSafety.MAIN_THREAD_ONLY) {
            diagnostics.accept(Diagnostic.warn(DiagnosticCodes.PROTECTION_PROVIDER_UNUSABLE,
                    "protection provider " + port.name() + " can only be asked on the main thread and this server is Folia; "
                            + "it is not used, and the event path still covers its plugin"));
            throw new IllegalArgumentException("protection provider " + port.name() + " is main-thread only");
        }
        Entry entry = new Entry(owner, port);
        entries.add(entry);
        return () -> entries.remove(entry);
    }

    /** Drops the providers a disabling plugin registered. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        dropProvidersOf(event.getPlugin());
    }

    void dropProvidersOf(Plugin owner) {
        entries.removeIf(entry -> entry.owner() == owner);
    }

    @Override
    public void close() {
        entries.clear();
    }

    @Override
    public ProtectionAnswer ask(ProtectionQuery query) {
        if (entries.isEmpty()) {
            return ProtectionAnswer.abstain("");
        }
        if (query.actor().isNone() && !settings.askWithoutActor()) {
            return ProtectionAnswer.abstain("");
        }
        boolean owned = regions.ownedHere(query.location());
        ProtectionActor actor = query.actor();
        if (actor.online().isPresent() && !regions.ownedHere(actor.online().get())) {
            actor = actor.asOffline(offlinePlayers.apply(actor.id().orElseThrow()));
        }
        ProtectionQuery asked = actor == query.actor() ? query
                : new ProtectionQuery(query.action(), actor, query.location(), query.victim());
        List<String> allowed = new ArrayList<>();
        for (Entry entry : entries) {
            ProtectionAnswer answer = askOne(entry.port(), asked, owned);
            switch (answer.verdict()) {
                case DENY -> {
                    return answer;
                }
                case ALLOW -> allowed.add(answer.provider());
                case ABSTAIN -> { }
            }
        }
        return allowed.isEmpty() ? ProtectionAnswer.abstain("") : ProtectionAnswer.allow(String.join(", ", allowed));
    }

    @Override
    public CompletionStage<ProtectionAnswer> askAt(ProtectionQuery query) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(ProtectionAnswer.abstain(""));
        }
        return regions.at(query.location(), () -> ask(query)).handle((answer, failure) -> {
            if (failure != null) {
                report("hop", "the hop to the owning region failed: " + failure);
                return ProtectionAnswer.deny("protection", "the question could not be put to the owning region");
            }
            return answer;
        });
    }

    private ProtectionAnswer askOne(ProtectionPort port, ProtectionQuery query, boolean owned) {
        if (!owned && safetyOf(port) != ProtectionSafety.ANY_THREAD) {
            report(port.name(), "asked from a thread that does not own " + describe(query));
            return ProtectionAnswer.deny(port.name(), "asked from the wrong thread");
        }
        try {
            ProtectionAnswer answer = switch (query.action()) {
                case BREAK -> port.canBreak(query.actor(), query.location());
                case PLACE -> port.canPlace(query.actor(), query.location());
                case INTERACT -> port.canInteract(query.actor(), query.location());
                case DAMAGE -> port.canDamage(query.actor(), query.victim());
                case USE -> port.canUse(query.actor(), query.location());
            };
            return answer == null ? ProtectionAnswer.abstain(port.name()) : answer;
        } catch (RuntimeException | LinkageError failure) {
            report(port.name(), failure.toString());
            return ProtectionAnswer.deny(port.name(), "the provider failed");
        }
    }

    private static ProtectionSafety safetyOf(ProtectionPort port) {
        try {
            ProtectionSafety safety = port.safety();
            return safety == null ? ProtectionSafety.OWNING_REGION : safety;
        } catch (RuntimeException | LinkageError failure) {
            return ProtectionSafety.MAIN_THREAD_ONLY;
        }
    }

    private static String describe(ProtectionQuery query) {
        var at = query.location();
        return query.action() + " at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    /** {@code MTR-0122}, once per provider per ten seconds. */
    private void report(String provider, String what) {
        long now = clock.getAsLong();
        boolean[] emit = {false};
        reported.compute(provider, (key, last) -> {
            if (last == null || now - last >= REPORT_INTERVAL_MS) {
                emit[0] = true;
                return now;
            }
            return last;
        });
        if (emit[0]) {
            diagnostics.accept(new Diagnostic(DiagnosticCodes.PROTECTION_PROVIDER_FAILED, Severity.WARN,
                    "protection provider " + provider + " failed (" + what + "); that call was denied", null, null,
                    "The call is denied so a broken provider never lets a change through. Update or remove the "
                            + "adapter, or set protection_mode: event to stop asking providers."));
        }
    }
}
