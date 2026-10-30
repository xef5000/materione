package dev.materione.integrations;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.logic.LogicApi;
import dev.materione.integrations.luckperms.LuckPermsPermissionProvider;
import dev.materione.integrations.proxy.BungeeCordTransfer;
import dev.materione.integrations.vault.VaultCurrencyIntegration;
import dev.materione.integrations.vault.VaultPermissionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * The soft-depend gate for the optional adapters (logic.md §4.5, §17, build.md §3). It decides, from
 * which optional plugins are enabled, what to register, and it is the only class that names the
 * adapters' types outside their own packages.
 *
 * <p><b>Guard.</b> An absent plugin is never checked for classes: each adapter is constructed inside the
 * branch that tested {@code isPluginEnabled} for its plugin, so the JVM loads Vault or LuckPerms types
 * only when those plugins run. An adapter that still fails to link is caught, reported as
 * {@code MTR-0061}, and skipped.
 *
 * <p><b>Selection.</b> Vault, when enabled, registers the {@code vault} currency integration
 * ({@code materione:vault}). Permissions: LuckPerms when enabled, else Vault's permission service when
 * Vault is enabled, else the session default (not registered here). Transfer: the BungeeCord plugin
 * channel when {@code proxyTransfer} is set; the transfer packet is the plugin's own provider.
 *
 * <p>{@link #refresh} runs at start, on reload, and whenever Vault or LuckPerms enables or disables.
 */
public final class Integrations implements Listener, AutoCloseable {

    /** The Vault plugin name as its {@code plugin.yml} declares it. */
    public static final String VAULT = "Vault";

    /** The LuckPerms plugin name as its {@code plugin.yml} declares it. */
    public static final String LUCKPERMS = "LuckPerms";

    private final Plugin host;
    private final LogicApi logic;
    private final DiagnosticsBus diagnostics;
    private final boolean proxyTransfer;
    private final List<AutoCloseable> installed = new ArrayList<>();

    /**
     * @param host the plugin that owns the registrations (its plugin manager delivers the enable and disable events)
     * @param logic the logic API whose registries and provider slots the adapters register into
     * @param diagnostics where a skipped adapter is reported
     * @param proxyTransfer whether {@code transfer_mode} selected the BungeeCord channel
     */
    public Integrations(Plugin host, LogicApi logic, DiagnosticsBus diagnostics, boolean proxyTransfer) {
        this.host = Objects.requireNonNull(host, "host");
        this.logic = Objects.requireNonNull(logic, "logic");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.proxyTransfer = proxyTransfer;
    }

    /** Listens for Vault and LuckPerms changing state, and makes the first selection. */
    public void start() {
        host.getServer().getPluginManager().registerEvents(this, host);
        refresh();
    }

    /**
     * Closes what an earlier refresh installed, then installs what the enabled plugins call for. Safe
     * to call any number of times.
     */
    public synchronized void refresh() {
        uninstall();
        var manager = host.getServer().getPluginManager();
        if (manager.isPluginEnabled(VAULT)) {
            attempt("Vault currency integration", () -> installed.add(logic.currencyIntegrations().register(
                    host, NamespacedId.of("materione", "vault"),
                    new VaultCurrencyIntegration())));
        }
        if (manager.isPluginEnabled(LUCKPERMS)) {
            attempt("LuckPerms permissions", () -> installed.add(
                    logic.providers().setPermissions(host, new LuckPermsPermissionProvider(host.getLogger()))));
        } else if (manager.isPluginEnabled(VAULT)) {
            attempt("Vault permissions", () -> installed.add(
                    logic.providers().setPermissions(host, new VaultPermissionProvider())));
        }
        if (proxyTransfer) {
            attempt("BungeeCord transfer", () -> installed.add(
                    logic.providers().setTransfer(host, new BungeeCordTransfer(host))));
        }
    }

    /** Vault or LuckPerms enabled or disabled: the selection may have changed. */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (isWatched(event.getPlugin().getName())) {
            refresh();
        }
    }

    /** Vault or LuckPerms disabled: its registrations leave, and the fallback answers instead. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (isWatched(event.getPlugin().getName())) {
            refresh();
        }
    }

    /** Removes every registration this gate installed and stops listening. Idempotent. */
    @Override
    public synchronized void close() {
        uninstall();
        HandlerList.unregisterAll(this);
    }

    private static boolean isWatched(String name) {
        return VAULT.equals(name) || LUCKPERMS.equals(name);
    }

    private void uninstall() {
        for (AutoCloseable registration : installed) {
            try {
                registration.close();
            } catch (Exception e) {
                host.getLogger().warning("an integration registration did not close cleanly: " + e);
            }
        }
        installed.clear();
    }

    /**
     * Runs one installation. A link failure or a runtime failure is a warning, never a failed enable:
     * the adapter is simply not installed.
     */
    private void attempt(String label, Installation installation) {
        try {
            installation.run();
        } catch (LinkageError | RuntimeException e) {
            host.getLogger().warning(label + " could not be installed: " + e);
            diagnostics.publish(Diagnostic.warn(DiagnosticCodes.INTEGRATION_SKIPPED,
                    label + " could not be installed (" + e + "); the default is used"));
        }
    }

    /** One installation step; a type so the adapters are referenced only inside the guarded branches. */
    @FunctionalInterface
    private interface Installation {
        void run();
    }
}
