package dev.materione.integrations.playerpoints;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import org.black_ixx.playerpoints.PlayerPoints;

/**
 * The {@code playerpoints} currency integration (logic.md §18): PlayerPoints' single points balance. It
 * takes no keys of its own, because PlayerPoints has one currency. It registers under
 * {@code materione:playerpoints} only while PlayerPoints is enabled, so a server without it never loads
 * these classes.
 */
public final class PlayerPointsCurrencyIntegration implements CurrencyIntegration {

    /** The plugin name as PlayerPoints' {@code plugin.yml} declares it. */
    public static final String PLUGIN = "PlayerPoints";

    public PlayerPointsCurrencyIntegration() {
        // loads PlayerPoints' type here, so an incompatible plugin fails inside the integration guard
        PlayerPoints.class.getName();
    }

    @Override
    public String id() {
        return "playerpoints";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("PlayerPoints",
                "The points PlayerPoints keeps for each player. Whole numbers only; it has no keys of its own.",
                List.<ParameterSchema>of(), "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new PlayerPointsCurrencyBackend();
    }
}
