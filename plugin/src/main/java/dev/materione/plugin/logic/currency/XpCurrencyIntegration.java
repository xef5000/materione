package dev.materione.plugin.logic.currency;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.probe.PlatformProbe;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The {@code xp} integration (logic.md §18): the player's experience as the currency. {@code unit:
 * points} (the default) counts total experience points; {@code unit: levels} counts levels.
 *
 * <p>Ported from ItemsAPI's XP integration, which computed the same total from the level and the
 * progress bar. The server already keeps that total, so this reads {@code getTotalExperience()}
 * rather than repeating Mojang's formula. Experience is whole: amounts round to the nearest whole
 * unit. Changes go through the player object on the global thread, which is where the money actions
 * run on Paper.
 */
public final class XpCurrencyIntegration implements CurrencyIntegration {

    @Override
    public String id() {
        return "xp";
    }

    @Override
    public CurrencyLookupCapability lookupCapability() {
        return new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE);
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("Experience",
                "The player's experience, as points (total experience) or as levels",
                List.of(new ParameterSchema("unit", ParameterType.ENUM, false, "points",
                        "What the currency counts", List.of("points", "levels"), null)),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new XpBackend("levels".equals(config.choice("unit", "points", List.of("points", "levels"))));
    }

    /** The backend for one unit: the player's level, or total experience points. */
    private static final class XpBackend implements CurrencyBackend {

        private static final boolean FOLIA = PlatformProbe.detectFoliaServer();
        private final boolean levels;

        XpBackend(boolean levels) {
            this.levels = levels;
        }

        @Override
        public Availability availability() {
            return Availability.AVAILABLE;
        }

        /**
         * Experience belongs to the player, so it is read and changed on the player's own region, which is
         * the thread the step is dispatched to. On Paper that is the main thread; on Folia the region.
         */
        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public CurrencyReadCapability readCapability() {
            return new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null);
        }

        @Override
        public boolean stableOnlineIdentityRead() {
            return true;
        }

        @Override
        public Object readBindingIdentity() {
            return this;
        }

        @Override
        public EconomyResult balanceForPlayer(Player player, UUID id) {
            return new EconomyResult.Succeeded(levels ? player.getLevel() : player.getTotalExperience());
        }

        @Override
        public EconomyResult balance(UUID player) {
            Player online = Bukkit.getPlayer(player);
            if (online == null) {
                return new EconomyResult.Failed("player is offline");
            }
            if (FOLIA && !Bukkit.isOwnedByCurrentRegion(online)) {
                return new EconomyResult.Failed("player moved or reconnected before the XP read");
            }
            return new EconomyResult.Succeeded(levels ? online.getLevel() : online.getTotalExperience());
        }

        @Override
        public EconomyResult has(UUID player, double amount) {
            EconomyResult held = balance(player);
            if (!(held instanceof EconomyResult.Succeeded ok)) {
                return held;
            }
            return ok.balance() >= Math.round(amount)
                    ? held : new EconomyResult.InsufficientFunds(ok.balance());
        }

        @Override
        public EconomyResult withdraw(UUID player, double amount) {
            EconomyResult held = has(player, amount);
            if (!(held instanceof EconomyResult.Succeeded)) {
                return held;
            }
            return change(player, -Math.round(amount));
        }

        @Override
        public EconomyResult deposit(UUID player, double amount) {
            return change(player, Math.round(amount));
        }

        private EconomyResult change(UUID player, long delta) {
            Player online = Bukkit.getPlayer(player);
            if (online == null) {
                return new EconomyResult.Failed("player is offline");
            }
            if (FOLIA && !Bukkit.isOwnedByCurrentRegion(online)) {
                return new EconomyResult.Failed("player moved or reconnected before the XP change");
            }
            if (levels) {
                online.giveExpLevels((int) delta);
            } else {
                online.giveExp((int) delta);
            }
            return balance(player);
        }
    }
}
