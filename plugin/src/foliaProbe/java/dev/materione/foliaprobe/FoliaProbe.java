package dev.materione.foliaprobe;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Local-only sentinel: a backend which must never be called on Folia without a capability. */
public final class FoliaProbe extends JavaPlugin implements Listener {
    @Override public void onEnable() {
        MaterioneAPI api = getServer().getServicesManager().load(MaterioneAPI.class);
        if (api == null) {
            throw new IllegalStateException("Materione service is not available");
        }
        api.logic().currencyIntegrations().register(this, NamespacedId.of("folia250", "sentinel"),
                new Sentinel());
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MTR250-SENTINEL-READY");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void dropped(PlayerDropItemEvent event) {
        getLogger().info("MTR250-DROP cancelled=" + event.isCancelled());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("rmw")) {
            FoliaProbeVerification.rmw(this);
            return true;
        }
        if (args.length == 2 && args[0].equals("offline")) {
            try {
                FoliaProbeVerification.offline(this, UUID.fromString(args[1]));
                return true;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        if (!(sender instanceof Player player) || args.length != 1 || !args[0].equals("migrate")) {
            return false;
        }
        var id = player.getUniqueId();
        player.getScheduler().execute(this, () -> {
            var origin = player.getLocation();
            var destination = origin.clone().add(512, 0, 0);
            player.teleportAsync(destination).whenComplete((moved, failure) ->
                    getLogger().info("MTR250-MIGRATION player=" + id
                            + " fromX=" + origin.getBlockX() + " toX=" + destination.getBlockX()
                            + " moved=" + moved + " error=" + (failure != null)));
        }, () -> getLogger().info("MTR250-MIGRATION-RETIRED player=" + id), 1);
        return true;
    }

    private final class Sentinel implements CurrencyIntegration {
        @Override public String id() { return "sentinel"; }
        @Override public SelfDescription describe() {
            return new SelfDescription("Unsafe sentinel", "Must not be called on Folia", List.of(), "1");
        }
        @Override public CurrencyBackend bind(ParamReader params) {
            return new CurrencyBackend() {
                @Override public EconomyResult balance(UUID player) {
                    getLogger().severe("MTR250-UNSAFE-CALL balance");
                    return new EconomyResult.Succeeded(999);
                }
                @Override public EconomyResult has(UUID player, double amount) {
                    getLogger().severe("MTR250-UNSAFE-CALL has");
                    return new EconomyResult.Succeeded(999);
                }
                @Override public EconomyResult withdraw(UUID player, double amount) {
                    return new EconomyResult.Failed("sentinel has no writes");
                }
                @Override public EconomyResult deposit(UUID player, double amount) {
                    return new EconomyResult.Failed("sentinel has no writes");
                }
            };
        }
    }
}
