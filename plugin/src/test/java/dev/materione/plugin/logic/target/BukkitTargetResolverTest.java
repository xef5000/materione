package dev.materione.plugin.logic.target;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetResolution;
import dev.materione.api.logic.TargetSpec;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The server-side resolver of {@code target:} (logic.md §39) against stand-ins for entities, with no server: what each
 * form resolves to, what it refuses and why, and the bounds of the nearby search. Lookups that need a running server
 * (an online player by name) are the harness's job ({@code PaperTargetAddressingIT}).
 */
class BukkitTargetResolverTest {

    private static final Set<TargetKind> PLAYERS = Set.of(TargetKind.PLAYER);
    private static final Set<TargetKind> ANY = Set.of(TargetKind.PLAYER, TargetKind.ENTITY);

    private final BukkitTargetResolver resolver = new BukkitTargetResolver();
    private final World world = stub(World.class, (method, args) -> {
        throw new AssertionError("unexpected world call: " + method.getName());
    });

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Answer answer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> type.getSimpleName() + " stub";
                };
            }
            return answer.answer(method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private interface Answer {
        Object answer(Method method, Object[] args);
    }

    /** A mob (a living entity) at a distance along x. */
    private LivingEntity mob(double x) {
        return stub(LivingEntity.class, (method, args) -> switch (method.getName()) {
            case "getLocation" -> new Location(world, x, 0, 0);
            case "getWorld" -> world;
            case "isValid" -> true;
            case "getUniqueId" -> java.util.UUID.fromString("12345678-0000-0000-0000-000000000001");
            case "getType" -> org.bukkit.entity.EntityType.ZOMBIE;
            default -> throw new AssertionError("unexpected mob call: " + method.getName());
        });
    }

    /** A player at a distance along x, in a game mode, optionally the run's player holding {@code nearby}. */
    private Player player(double x, GameMode mode, List<Entity> nearby, List<double[]> searches) {
        return stub(Player.class, (method, args) -> switch (method.getName()) {
            case "getLocation" -> new Location(world, x, 0, 0);
            case "getWorld" -> world;
            case "getGameMode" -> mode;
            case "isOnline" -> true;
            case "isValid" -> true;
            case "getNearbyEntities" -> {
                searches.add(new double[] {(Double) args[0], (Double) args[1], (Double) args[2]});
                yield nearby;
            }
            default -> throw new AssertionError("unexpected player call: " + method.getName());
        });
    }

    private MapLogicContext context(Player runner, Entity eventTarget, Map<String, Object> args) {
        var root = new LinkedHashMap<String, Object>();
        root.put("args", args);
        var context = new MapLogicContext(root);
        if (runner != null) {
            context.with(Facets.PLAYER, runner);
        }
        if (eventTarget != null) {
            context.with(Facets.EVENT_TARGET, eventTarget);
        }
        return context;
    }

    private TargetResolution resolve(TargetSpec spec, Set<TargetKind> admits, MapLogicContext context) {
        return resolver.resolve(spec, admits, context, ReadExecutor.INLINE).toCompletableFuture().join();
    }

    private TargetResolution search(TargetSpec.Nearby nearby, Set<TargetKind> admits, List<Entity> around) {
        var searches = new ArrayList<double[]>();
        Player runner = player(0, GameMode.SURVIVAL, around, searches);
        return resolve(nearby, admits, context(runner, null, Map.of()));
    }

    // --- event.target ----------------------------------------------------------------

    @Test
    void theEventTargetIsTheEntityTheTriggerCarries() {
        var zombie = mob(1);
        var resolution = resolve(new TargetSpec.Event(), ANY, context(null, zombie, Map.of()));

        assertThat(resolution.targets()).singleElement().satisfies(target -> {
            assertThat(target.anchor()).isSameAs(zombie);
            assertThat(target.kind()).isEqualTo(TargetKind.ENTITY);
        });
    }

    @Test
    void anEventWithNoTargetOrAGoneOneIsRefused() {
        assertThat(resolve(new TargetSpec.Event(), ANY, context(null, null, Map.of())).reason())
                .isEqualTo("this event carries no target");

        var dead = stub(LivingEntity.class, (method, args) -> switch (method.getName()) {
            case "isValid" -> false;
            default -> throw new AssertionError(method.getName());
        });
        assertThat(resolve(new TargetSpec.Event(), ANY, context(null, dead, Map.of())).reason())
                .isEqualTo("the target is no longer in the world");
    }

    @Test
    void anOfflinePlayerIsGoneEvenIfTheEntityObjectLives() {
        var quit = stub(Player.class, (method, args) -> switch (method.getName()) {
            case "isOnline" -> false;
            default -> throw new AssertionError(method.getName());
        });
        assertThat(resolve(new TargetSpec.Event(), ANY, context(null, quit, Map.of())).reason())
                .isEqualTo("the target is no longer in the world");
    }

    @Test
    void aPlayerOnlyActionRefusesAMobAndAcceptsAPlayer() {
        var refused = resolve(new TargetSpec.Event(), PLAYERS, context(null, mob(1), Map.of()));
        assertThat(refused.targets()).isEmpty();
        assertThat(refused.reason()).startsWith("the action cannot act on a entity (");

        var online = player(1, GameMode.SURVIVAL, List.of(), new ArrayList<>());
        assertThat(resolve(new TargetSpec.Event(), PLAYERS, context(null, online, Map.of())).targets())
                .singleElement().satisfies(target -> assertThat(target.kind()).isEqualTo(TargetKind.PLAYER));
    }

    // --- named players -----------------------------------------------------------------

    @Test
    void anArgumentThatIsNotSetOrAnEmptyNameIsRefused() {
        assertThat(resolve(new TargetSpec.Arg("to"), PLAYERS, context(null, null, Map.of())).reason())
                .isEqualTo("argument 'to' is not set");
        assertThat(resolve(new TargetSpec.Arg("to"), PLAYERS, context(null, null, Map.of("to", "  "))).reason())
                .isEqualTo("the player name is empty");
    }

    @Test
    void aPlayerTemplateThatRendersNothingIsRefused() {
        var spec = new TargetSpec.Player(EngineExpressions.INSTANCE.template("${args.missing}"));
        assertThat(resolve(spec, PLAYERS, context(null, null, Map.of())).reason()).isEqualTo("the player name is empty");
    }

    // --- nearby ---------------------------------------------------------------------------

    @Test
    void nearbyAsksTheRunsPlayerForABoxOfTheRadiusAndKeepsTheNearestWithinTheSphere() {
        var near = mob(2);
        var far = mob(5);
        var corner = mob(9); // inside the box of radius 6 only if it were a cube; the sphere refuses it
        var searches = new ArrayList<double[]>();
        Player runner = player(0, GameMode.SURVIVAL, new ArrayList<>(List.of(far, near, corner)), searches);

        var resolution = resolve(new TargetSpec.Nearby(6, null, 5), ANY, context(runner, null, Map.of()));

        assertThat(searches).singleElement().satisfies(box -> assertThat(box).containsExactly(6d, 6d, 6d));
        assertThat(resolution.targets()).extracting(ResolvedTargetAnchor::of).containsExactly(near, far);
    }

    @Test
    void nearbyNeverSelectsTheRunsPlayerAndTakesAtMostLimit() {
        var searches = new ArrayList<double[]>();
        var nearby = new ArrayList<Entity>(List.of(mob(1), mob(2), mob(3)));
        Player runner = player(0, GameMode.SURVIVAL, nearby, searches);
        nearby.add(runner); // the server's box includes the player it is around

        var resolution = resolve(new TargetSpec.Nearby(8, null, 2), ANY, context(runner, null, Map.of()));

        assertThat(resolution.targets()).hasSize(2).extracting(ResolvedTargetAnchor::of).doesNotContain(runner);
    }

    @Test
    void aPlayerOnlyActionSelectsPlayersAndSkipsSpectatorsAndMobs() {
        var survivor = player(3, GameMode.SURVIVAL, List.of(), new ArrayList<>());
        var watcher = player(1, GameMode.SPECTATOR, List.of(), new ArrayList<>());
        var resolution = search(new TargetSpec.Nearby(8, null, 5), PLAYERS, List.of(mob(1), watcher, survivor));

        assertThat(resolution.targets()).extracting(ResolvedTargetAnchor::of).containsExactly(survivor);
    }

    @Test
    void aMobThatJustDiedIsNotATargetEvenThoughItIsStillInTheBox() {
        var corpse = stub(LivingEntity.class, (method, args) -> switch (method.getName()) {
            case "isValid" -> false; // dying: still in the box for a second, no longer in the world
            case "getLocation" -> new Location(world, 0, 0, 0);
            case "getWorld" -> world;
            default -> throw new AssertionError("unexpected corpse call: " + method.getName());
        });
        var alive = mob(3);

        assertThat(search(new TargetSpec.Nearby(8, null, 1), ANY, List.of(corpse, alive)).targets())
                .extracting(ResolvedTargetAnchor::of).containsExactly(alive);
        assertThat(search(new TargetSpec.Nearby(8, null, 1), ANY, List.of(corpse)).reason())
                .isEqualTo("no entity within 8 blocks");
    }

    @Test
    void nothingNearbyIsARefusalThatSaysWhatWasLookedFor() {
        assertThat(search(new TargetSpec.Nearby(8, null, 1), ANY, List.of()).reason()).isEqualTo("no entity within 8 blocks");
        assertThat(search(new TargetSpec.Nearby(2.5, "zombie", 1), ANY, List.of()).reason())
                .isEqualTo("no zombie within 2.5 blocks");
    }

    @Test
    void nearbyNeedsAPlayerToSearchAround() {
        assertThat(resolve(new TargetSpec.Nearby(8, null, 1), ANY, context(null, null, Map.of())).reason())
                .isEqualTo("the run has no player to search around");
    }

    @Test
    void aSearchBoxAnotherRegionOwnsIsARefusalNotAnException() {
        var runner = stub(Player.class, (method, args) -> {
            if (method.getName().equals("getNearbyEntities")) {
                throw new IllegalStateException("Cannot read world asynchronously");
            }
            throw new AssertionError(method.getName());
        });
        assertThat(resolve(new TargetSpec.Nearby(8, null, 1), ANY, context(runner, null, Map.of())).reason())
                .isEqualTo("the search area is not owned by this region");
    }

    /** The entity a resolved target holds. */
    private static final class ResolvedTargetAnchor {
        static Object of(dev.materione.api.logic.ResolvedTarget target) {
            return target.anchor();
        }
    }
}
