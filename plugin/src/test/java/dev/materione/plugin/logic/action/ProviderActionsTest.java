package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.EconomyProvider;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The five provider actions (logic.md §4.5, §9 rule 6): each parses and rejects like the rest, runs
 * against fake providers (ok, insufficient, refused, no provider), and reports availability from
 * the provider that is installed at the moment it is asked. No server: the player is a stand-in with
 * a fixed UUID, and the providers are in-memory.
 */
class ProviderActionsTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    private static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player " + id;
                    default -> throw new AssertionError("unexpected player call: " + method.getName());
                });
    }

    private static MapLogicContext withPlayer() {
        return new MapLogicContext(Map.of()).with(Facets.PLAYER, player(ALEX));
    }

    /** An in-memory economy: balances per player; a withdrawal the balance cannot cover takes nothing. */
    static final class FakeEconomy implements EconomyProvider {
        final Map<UUID, Double> balances = new HashMap<>();
        String refusal;

        @Override
        public String name() {
            return "fake economy";
        }

        @Override
        public EconomyResult balance(UUID player) {
            return new EconomyResult.Succeeded(balances.getOrDefault(player, 0d));
        }

        @Override
        public EconomyResult has(UUID player, double amount) {
            double balance = balances.getOrDefault(player, 0d);
            return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
        }

        @Override
        public EconomyResult withdraw(UUID player, double amount) {
            if (refusal != null) {
                return new EconomyResult.Failed(refusal);
            }
            EconomyResult held = has(player, amount);
            if (held instanceof EconomyResult.InsufficientFunds) {
                return held;
            }
            double after = balances.getOrDefault(player, 0d) - amount;
            balances.put(player, after);
            return new EconomyResult.Succeeded(after);
        }

        @Override
        public EconomyResult deposit(UUID player, double amount) {
            if (refusal != null) {
                return new EconomyResult.Failed(refusal);
            }
            double after = balances.getOrDefault(player, 0d) + amount;
            balances.put(player, after);
            return new EconomyResult.Succeeded(after);
        }
    }

    /** In-memory permission nodes; {@code refusal} makes every request refuse with that reason. */
    static final class FakePermissions implements PermissionProvider {
        final Map<UUID, Set<String>> nodes = new HashMap<>();
        String refusal;

        @Override
        public String name() {
            return "fake permissions";
        }

        @Override
        public ProviderResult grant(UUID player, String node) {
            if (refusal != null) {
                return ProviderResult.failed(refusal);
            }
            nodes.computeIfAbsent(player, id -> new HashSet<>()).add(node);
            return ProviderResult.OK;
        }

        @Override
        public ProviderResult revoke(UUID player, String node) {
            if (refusal != null) {
                return ProviderResult.failed(refusal);
            }
            nodes.getOrDefault(player, new HashSet<>()).remove(node);
            return ProviderResult.OK;
        }

        @Override
        public ProviderResult grantFor(UUID player, String node, Duration duration) {
            return grant(player, node);
        }
    }

    static final class FakeTransfer implements ServerTransfer {
        final List<String> sent = new ArrayList<>();
        String refusal;

        @Override
        public String name() {
            return "fake transfer";
        }

        @Override
        public ProviderResult send(UUID player, String server) {
            if (refusal != null) {
                return ProviderResult.failed(refusal);
            }
            sent.add(player + "->" + server);
            return ProviderResult.OK;
        }
    }

    /** Providers the test installs and removes at will; the actions read them at each use. */
    static final class FakeProviders implements LogicProviders {
        Optional<EconomyProvider> economy = Optional.empty();
        Optional<ServerTransfer> transfer = Optional.empty();
        final PermissionProvider permissions;

        FakeProviders(PermissionProvider permissions) {
            this.permissions = permissions;
        }

        @Override
        public Optional<EconomyProvider> economy() {
            return economy;
        }

        @Override
        public PermissionProvider permissions() {
            return permissions;
        }

        @Override
        public Optional<ServerTransfer> transfer() {
            return transfer;
        }

        @Override
        public ProviderRegistration setEconomy(org.bukkit.plugin.Plugin owner, EconomyProvider provider) {
            economy = Optional.of(provider);
            return () -> economy = Optional.empty();
        }

        @Override
        public ProviderRegistration setPermissions(org.bukkit.plugin.Plugin owner, PermissionProvider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderRegistration setTransfer(org.bukkit.plugin.Plugin owner, ServerTransfer provider) {
            transfer = Optional.of(provider);
            return () -> transfer = Optional.empty();
        }
    }

    @Test
    void theFiveProviderActionsDescribeThemselvesWithCategoryThreadingAndShorthand() {
        var actions = BuiltinLogic.providerActions(new FakeProviders(new FakePermissions()));

        assertThat(actions).extracting(ActionType::id)
                .containsExactly("give_money", "take_money", "give_permission", "take_permission", "connect");
        assertThat(actions).extracting(action -> action.describe().category()).containsExactly(
                LogicCategory.ECONOMY, LogicCategory.ECONOMY, LogicCategory.SERVER, LogicCategory.SERVER,
                LogicCategory.SERVER);
        assertThat(actions).extracting(ActionType::threading).containsExactly(
                Threading.GLOBAL, Threading.GLOBAL, Threading.PLAYER_REGION, Threading.PLAYER_REGION,
                Threading.PLAYER_REGION);
        assertThat(actions).extracting(action -> action.describe().shorthand())
                .containsExactly("amount", "amount", "permission", "permission", "server");
        assertThat(actions).allSatisfy(action -> assertThat(action.describe().self().parameters()).isNotEmpty());
    }

    @Nested
    class Money {

        @Test
        void giveAndTakeAcceptPositiveAmountsAndRejectTheRest() {
            var give = new GiveMoneyAction(new FakeProviders(new FakePermissions()));
            var take = new TakeMoneyAction(new FakeProviders(new FakePermissions()));
            for (ActionType<?> action : List.of(give, take)) {
                assertThat(parseOf(action, params("amount", 100))).isNotNull();
                assertThat(parseOf(action, params("amount", 2.5))).isNotNull();
            }
            assertThatThrownBy(() -> give.parse(reader(params()))).hasMessage("amount is required");
            assertThatThrownBy(() -> give.parse(reader(params("amount", 0)))).hasMessage("amount must be above 0");
            assertThatThrownBy(() -> give.parse(reader(params("amount", "100"))))
                    .hasMessageStartingWith("amount must be a finite number");
            assertThatThrownBy(() -> take.parse(reader(params("amount", -5))))
                    .hasMessageStartingWith("amount must be a finite number");
            assertThatThrownBy(() -> take.parse(reader(params("amount", 1.0E13))))
                    .hasMessageStartingWith("amount must be a finite number");
        }

        @Test
        void giveAddsToTheBalanceAndSucceeds() {
            var economy = new FakeEconomy();
            var providers = new FakeProviders(new FakePermissions());
            providers.economy = Optional.of(economy);
            var give = new GiveMoneyAction(providers);

            assertThat(give.execute(give.parse(reader(params("amount", 150))), withPlayer())).isEqualTo(ActionOutcome.OK);
            assertThat(economy.balances).containsEntry(ALEX, 150d);
        }

        @Test
        void takeSucceedsWhenTheBalanceCoversTheAmount() {
            var economy = new FakeEconomy();
            economy.balances.put(ALEX, 200d);
            var providers = new FakeProviders(new FakePermissions());
            providers.economy = Optional.of(economy);
            var take = new TakeMoneyAction(providers);

            assertThat(take.execute(take.parse(reader(params("amount", 75))), withPlayer())).isEqualTo(ActionOutcome.OK);
            assertThat(economy.balances).containsEntry(ALEX, 125d);
        }

        @Test
        void takeFailsWithInsufficientFundsAndTakesNothing() {
            var economy = new FakeEconomy();
            economy.balances.put(ALEX, 5d);
            var providers = new FakeProviders(new FakePermissions());
            providers.economy = Optional.of(economy);
            var take = new TakeMoneyAction(providers);

            ActionOutcome outcome = take.execute(take.parse(reader(params("amount", 10))), withPlayer());

            assertThat(outcome).isEqualTo(ActionOutcome.failed("insufficient funds: balance 5.0, needs 10.0"));
            assertThat(economy.balances).containsEntry(ALEX, 5d);
        }

        @Test
        void aProviderFailureSurfacesItsReason() {
            var economy = new FakeEconomy();
            economy.refusal = "the bank is closed";
            var providers = new FakeProviders(new FakePermissions());
            providers.economy = Optional.of(economy);
            var give = new GiveMoneyAction(providers);

            assertThat(give.execute(give.parse(reader(params("amount", 1))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("the bank is closed"));
        }

        @Test
        void withNoEconomyTheActionsAreUnavailableAndFailWithTheSentence() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);
            var take = new TakeMoneyAction(providers);

            assertThat(give.availability().available()).isFalse();
            assertThat(give.availability().reason()).contains("economy provider");
            assertThat(take.availability().available()).isFalse();
            assertThat(give.execute(give.parse(reader(params("amount", 1))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("no economy provider"));
            assertThat(take.execute(take.parse(reader(params("amount", 1))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("no economy provider"));
        }

        @Test
        void availabilityFollowsTheProviderInstalledAtTheMomentItIsAsked() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);
            assertThat(give.availability()).isNotEqualTo(Availability.AVAILABLE);

            providers.economy = Optional.of(new FakeEconomy());
            assertThat(give.availability()).isEqualTo(Availability.AVAILABLE);
        }

        @Test
        void aRunWithoutAPlayerFailsInsteadOfThrowing() {
            var providers = new FakeProviders(new FakePermissions());
            providers.economy = Optional.of(new FakeEconomy());
            var take = new TakeMoneyAction(providers);

            assertThat(take.execute(take.parse(reader(params("amount", 1))), new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Permissions {

        @Test
        void parsesAPermissionNodeAndRejectsMalformedOnes() {
            var give = new GivePermissionAction(new FakeProviders(new FakePermissions()));

            assertThat(give.parse(reader(params("permission", "example.fly"))).node()).isEqualTo("example.fly");
            assertThat(give.parse(reader(params("permission", "shop.*"))).node()).isEqualTo("shop.*");
            assertThatThrownBy(() -> give.parse(reader(params()))).hasMessage("permission is required");
            assertThatThrownBy(() -> give.parse(reader(params("permission", "a b"))))
                    .hasMessageStartingWith("permission must be a node");
            assertThatThrownBy(() -> give.parse(reader(params("permission", "example..fly"))))
                    .hasMessageStartingWith("permission must be a node");
        }

        @Test
        void giveAndTakeGrantAndRevokeThroughTheActivePermissionProvider() {
            var permissions = new FakePermissions();
            var providers = new FakeProviders(permissions);
            var give = new GivePermissionAction(providers);
            var take = new TakePermissionAction(providers);

            assertThat(give.execute(give.parse(reader(params("permission", "example.fly"))), withPlayer()))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(permissions.nodes.get(ALEX)).containsExactly("example.fly");
            assertThat(take.execute(take.parse(reader(params("permission", "example.fly"))), withPlayer()))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(permissions.nodes.get(ALEX)).isEmpty();
        }

        @Test
        void aRefusedRequestFailsWithTheProvidersReason() {
            var permissions = new FakePermissions();
            permissions.refusal = "no permission plugin is registered with Vault";
            var give = new GivePermissionAction(new FakeProviders(permissions));

            assertThat(give.execute(give.parse(reader(params("permission", "a.b"))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("no permission plugin is registered with Vault"));
        }

        @Test
        void aRunWithoutAPlayerFailsAndThePermissionActionsAreAlwaysAvailable() {
            var give = new GivePermissionAction(new FakeProviders(new FakePermissions()));

            assertThat(give.execute(give.parse(reader(params("permission", "a.b"))), new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
            assertThat(give.availability()).isEqualTo(Availability.AVAILABLE);
        }
    }

    @Nested
    class Connect {

        @Test
        void parsesTheServerNameAndRequiresIt() {
            var connect = new ConnectAction(new FakeProviders(new FakePermissions()));

            assertThat(connect.parse(reader(params("server", "lobby"))).server()).isEqualTo("lobby");
            assertThatThrownBy(() -> connect.parse(reader(params()))).hasMessage("server is required");
        }

        @Test
        void sendsThePlayerThroughTheTransferAndReportsARefusal() {
            var providers = new FakeProviders(new FakePermissions());
            var transfer = new FakeTransfer();
            providers.transfer = Optional.of(transfer);
            var connect = new ConnectAction(providers);

            assertThat(connect.execute(connect.parse(reader(params("server", "lobby"))), withPlayer()))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(transfer.sent).containsExactly(ALEX + "->lobby");

            transfer.refusal = "player is offline";
            assertThat(connect.execute(connect.parse(reader(params("server", "lobby"))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("player is offline"));
        }

        @Test
        void withNoTransferItIsUnavailableAndFailsCleanly() {
            var connect = new ConnectAction(new FakeProviders(new FakePermissions()));

            assertThat(connect.availability().available()).isFalse();
            assertThat(connect.availability().reason()).contains("transfer");
            assertThat(connect.execute(connect.parse(reader(params("server", "lobby"))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("no server transfer"));
        }
    }

    private static Object parseOf(ActionType<?> action, Map<String, Object> params) {
        return switch (action) {
            case GiveMoneyAction give -> give.parse(reader(params));
            case TakeMoneyAction take -> take.parse(reader(params));
            default -> throw new IllegalArgumentException(action.id());
        };
    }
}
