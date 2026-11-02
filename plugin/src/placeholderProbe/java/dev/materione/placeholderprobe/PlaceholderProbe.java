package dev.materione.placeholderprobe;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable-server-only instrumentation; never shipped in the Materione plugin jar. */
public final class PlaceholderProbe extends JavaPlugin {
    private final AtomicInteger lookupBinds = new AtomicInteger();
    private final AtomicInteger readBinds = new AtomicInteger();
    private final AtomicInteger availabilityCalls = new AtomicInteger();
    private final AtomicInteger balanceCalls = new AtomicInteger();
    private final AtomicInteger expansionCalls = new AtomicInteger();
    private volatile String value = "3";

    private final PlaceholderExpansion expansion = new PlaceholderExpansion() {
        @Override public String getIdentifier() { return "mtr252"; }
        @Override public String getAuthor() { return "Materione QA"; }
        @Override public String getVersion() { return "1"; }
        @Override public boolean persist() { return true; }
        @Override public String onRequest(OfflinePlayer player, String params) {
            if (!params.equals("balance")) return null;
            expansionCalls.incrementAndGet();
            return value;
        }
    };

    @Override public void onEnable() {
        MaterioneAPI api = getServer().getServicesManager().load(MaterioneAPI.class);
        if (api == null || !expansion.register()) {
            throw new IllegalStateException("Materione service or PlaceholderAPI expansion unavailable");
        }
        api.logic().currencyIntegrations().register(this, NamespacedId.of("mtr252", "lookup_unsafe"),
                new Sentinel(false));
        api.logic().currencyIntegrations().register(this, NamespacedId.of("mtr252", "read_unsafe"),
                new Sentinel(true));
        getLogger().info("MTR252-READY papi=" + getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")
                + " probe=" + PlaceholderAPI.isRegistered("mtr252")
                + " materione=" + PlaceholderAPI.isRegistered("materione"));
    }

    @Override public void onDisable() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && expansion.isRegistered()) {
            expansion.unregister();
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0]) {
            case "value" -> {
                if (args.length != 2) return false;
                try {
                    if (!Double.isFinite(Double.parseDouble(args[1]))) return false;
                } catch (NumberFormatException failure) {
                    return false;
                }
                value = args[1];
                sender.sendMessage("MTR252-VALUE " + value);
            }
            case "register" -> sender.sendMessage("MTR252-REGISTER " + expansion.register());
            case "unregister" -> sender.sendMessage("MTR252-UNREGISTER " + expansion.unregister());
            case "stats" -> sender.sendMessage(stats());
            case "sample" -> {
                if (args.length != 2) return false;
                Player player = getServer().getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage("MTR252-NO-PLAYER");
                    return true;
                }
                player.getScheduler().execute(this, () -> {
                    String text = PlaceholderAPI.setPlaceholders(player,
                            "xp=%materione_balance_probe_qa_balance_xp%"
                            + " xpFormatted=%materione_balance_probe_qa_balance_xp_formatted%"
                            + " custom=%materione_balance_probe_qa_balance_custom%"
                            + " customFormatted=%materione_balance_probe_qa_balance_custom_formatted%"
                            + " unsafeLookup=%materione_balance_probe_qa_balance_lookup_unsafe%"
                            + " unsafeRead=%materione_balance_probe_qa_balance_read_unsafe%"
                            + " unknown=%materione_balance_probe_missing%");
                    getLogger().info("MTR252-SAMPLE " + text + " " + stats());
                }, () -> getLogger().warning("MTR252-SAMPLE-RETIRED"), 1);
            }
            case "papi-disable" -> {
                if (args.length != 2) return false;
                Player player = getServer().getPlayerExact(args[1]);
                var hook = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion("materione");
                Plugin papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
                if (player == null || papi == null || hook == null) {
                    sender.sendMessage("MTR252-DISABLE-UNAVAILABLE");
                    return true;
                }
                getServer().getPluginManager().disablePlugin(papi);
                boolean disabled = !papi.isEnabled();
                String disabledValue = hook.onRequest(player, "balance_probe_qa_balance_custom");
                sender.sendMessage("MTR252-DISABLE disabled=" + disabled + " empty=" + "".equals(disabledValue));
            }
            default -> { return false; }
        }
        return true;
    }

    private String stats() {
        return "MTR252-STATS lookupBind=" + lookupBinds.get() + " readBind=" + readBinds.get()
                + " availability=" + availabilityCalls.get() + " balance=" + balanceCalls.get()
                + " expansion=" + expansionCalls.get();
    }

    private final class Sentinel implements CurrencyIntegration {
        private final boolean allowLookup;
        Sentinel(boolean allowLookup) { this.allowLookup = allowLookup; }
        @Override public String id() { return allowLookup ? "read_unsafe" : "lookup_unsafe"; }
        @Override public SelfDescription describe() {
            return new SelfDescription("Unsafe probe", "Must not reach unsafe callbacks on Folia", List.of(), "1");
        }
        @Override public CurrencyLookupCapability lookupCapability() {
            return allowLookup ? new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE)
                    : CurrencyLookupCapability.unsupported();
        }
        @Override public CurrencyBackend bind(ParamReader config) {
            (allowLookup ? readBinds : lookupBinds).incrementAndGet();
            return new CurrencyBackend() {
                @Override public Availability availability() {
                    availabilityCalls.incrementAndGet();
                    return Availability.AVAILABLE;
                }
                @Override public EconomyResult balance(UUID player) {
                    balanceCalls.incrementAndGet();
                    return new EconomyResult.Succeeded(999);
                }
                @Override public EconomyResult has(UUID player, double amount) {
                    balanceCalls.incrementAndGet();
                    return new EconomyResult.Succeeded(999);
                }
                @Override public EconomyResult withdraw(UUID player, double amount) {
                    return new EconomyResult.Failed("probe is read-only");
                }
                @Override public EconomyResult deposit(UUID player, double amount) {
                    return new EconomyResult.Failed("probe is read-only");
                }
            };
        }
    }
}
