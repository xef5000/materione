package dev.materione.integrations.protection;

import static dev.materione.integrations.protection.Doubles.location;
import static dev.materione.integrations.protection.Doubles.victim;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer.Verdict;
import dev.materione.api.protection.ProtectionPort;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/** The PlotSquared adapter's logic over a test double of PlotSquared (no plugin, no server). */
class PlotSquaredAdapterTest {

    private PlotSquaredAdapter.Kind kind = PlotSquaredAdapter.Kind.OWNED;
    private boolean added;

    private final ProtectionPort port = PlotSquaredAdapter.port(new PlotSquaredAdapter.Api() {
        @Override public PlotSquaredAdapter.Kind kind(Location location) { return kind; }

        @Override public boolean added(Location location, UUID player) {
            return added && player.equals(Doubles.ALEX);
        }
    });

    @Test
    void outsideAPlotWorldNothingIsCovered() {
        kind = PlotSquaredAdapter.Kind.NOT_PLOT_WORLD;

        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canBreak(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void aPlotIsOpenToItsOwnersTrustedAndMembersAndClosedToStrangers() {
        added = true;
        assertThat(port.canPlace(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canPlace(Doubles.offline(), location()).verdict()).isEqualTo(Verdict.ALLOW);

        added = false;
        var stranger = port.canPlace(Doubles.online(), location());
        assertThat(stranger.denied()).isTrue();
        assertThat(stranger.provider()).isEqualTo("PlotSquared");
        assertThat(stranger.reason()).contains("someone else");
    }

    @Test
    void anAdminBypassPermissionOpensAPlotAnUnownedPlotAndARoad() {
        assertThat(port.canBreak(Doubles.online("plots.admin.build.other"), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canBreak(Doubles.online("plots.admin.build.unowned"), location()).denied()).isTrue();

        kind = PlotSquaredAdapter.Kind.UNOWNED;
        assertThat(port.canBreak(Doubles.online("plots.admin.build.unowned"), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canBreak(Doubles.online(), location()).denied()).isTrue();

        kind = PlotSquaredAdapter.Kind.ROAD;
        assertThat(port.canBreak(Doubles.online("plots.admin.build.road"), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canBreak(Doubles.online(), location()).denied()).isTrue();
    }

    @Test
    void aPlayerKnownByIdHasNoPermissionsToBypassWith() {
        kind = PlotSquaredAdapter.Kind.ROAD;

        assertThat(port.canBreak(Doubles.offline(), location()).denied()).isTrue();
    }

    @Test
    void noActorIsDeniedAnywhereInAPlotWorld() {
        for (var each : PlotSquaredAdapter.Kind.values()) {
            kind = each;
            assertThat(port.canPlace(ProtectionActor.none(), location()).verdict())
                    .isEqualTo(each == PlotSquaredAdapter.Kind.NOT_PLOT_WORLD ? Verdict.ABSTAIN : Verdict.DENY);
        }
    }

    @Test
    void damageAndItemUseGetNoOpinion() {
        assertThat(port.canDamage(Doubles.online(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canUse(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }
}
