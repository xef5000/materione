package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionPort;
import java.util.List;
import java.util.function.Supplier;

/**
 * The protection adapters, one per plugin (protection.md section 9). {@link dev.materione.integrations.Integrations} registers a
 * candidate's port while its plugin is enabled; the factory is a lambda, so an adapter's class (and the plugin classes it links
 * against) is not touched until the plugin's presence has been checked and the factory runs. A factory that cannot link is caught
 * by the gate and reported as {@code MTR-0124}; the plugin's own events still reach it through the event path.
 *
 * <p>Not here, and why (protection.md section 9): Lands and Residence publish their APIs on JitPack only, which is not reliable
 * enough for CI; FactionsUUID publishes no maintained API artifact. Their plugins are covered by the event path, and an
 * addon can register a port for any of them through {@code api.logic().protection()}.
 */
public final class ProtectionAdapters {

    /** One adapter: the plugin it needs, and how to build its port once that plugin is known to run. */
    public record Candidate(String plugin, Supplier<ProtectionPort> factory) {}

    private ProtectionAdapters() {}

    /** The adapters, in the order their providers are asked. */
    public static List<Candidate> all() {
        return List.of(
                new Candidate(WorldGuardAdapter.PLUGIN, () -> WorldGuardAdapter.create()),
                new Candidate(GriefPreventionAdapter.PLUGIN, () -> GriefPreventionAdapter.create()),
                new Candidate(TownyAdapter.PLUGIN, () -> TownyAdapter.create()),
                new Candidate(PlotSquaredAdapter.PLUGIN, () -> PlotSquaredAdapter.create()),
                new Candidate(HuskClaimsAdapter.PLUGIN, () -> HuskClaimsAdapter.create()),
                new Candidate(HuskTownsAdapter.PLUGIN, () -> HuskTownsAdapter.create()),
                new Candidate(BentoBoxAdapter.PLUGIN, () -> BentoBoxAdapter.create()));
    }

    /** The plugin names the adapters watch: a change to one of them refreshes the gate. */
    public static List<String> plugins() {
        return all().stream().map(Candidate::plugin).toList();
    }
}
