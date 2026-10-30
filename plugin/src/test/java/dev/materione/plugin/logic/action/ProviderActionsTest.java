package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
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
 * The provider actions (logic.md §4.5, §18): each parses and rejects like the rest, runs against an
 * in-memory currency backend and fake permission and transfer providers (ok, insufficient, refused, not
 * installed), and reports availability from what is installed at the moment it is asked. No server: the
 * player is a stand-in with a fixed UUID.
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

    /** In-memory money: balances per player; a withdrawal the balance cannot cover takes nothing. */
    static final class FakeBackend implements CurrencyBackend {
        final Map<UUID, Double> balances = new HashMap<>();
        String refusal;
        boolean up = true;
        Threading threading = Threading.GLOBAL;

        @Override
        public Availability availability() {
            return up ? Availability.AVAILABLE : Availability.unavailable("the fake economy is down");
        }

        @Override
        public Threading threading() {
            return threading;
        }

        @Override
        public EconomyResult balance(UUID player) {
            return new EconomyResult.Succeeded(balances.getOrDefault(player, 0d));
        }

        @Override
        public EconomyResult has(UUID player, double amount) {
            double balance = balances.getOrDefault(player, 0d);
            return balance >= amount
                    ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
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

    /** A fake integration: binds to the one backend the test holds, and rejects a config without {@code mode}. */
    static final class FakeIntegration implements CurrencyIntegration {
        final FakeBackend backend;

        FakeIntegration(FakeBackend backend) {
            this.backend = backend;
        }

        @Override
        public String id() {
            return "vault";
        }

        @Override
        public dev.materione.api.registry.SelfDescription describe() {
            return new dev.materione.api.registry.SelfDescription("fake", "fake", List.of(), "2.0.0");
        }

        @Override
        public CurrencyBackend bind(ParamReader config) {
            if (config.has("mode") && !"good".equals(config.text("mode"))) {
                throw new IllegalArgumentException("mode must be good");
            }
            return backend;
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
        final FakeBackend backend = new FakeBackend();
        final MaterioneCurrencyCatalog currencies;
        Optional<ServerTransfer> transfer = Optional.empty();
        final PermissionProvider permissions;
        /** Whether the vault integration is registered; toggled to model Vault arriving or leaving. */
        boolean vaultInstalled = true;

        FakeProviders(PermissionProvider permissions) {
            this.permissions = permissions;
            this.currencies = new MaterioneCurrencyCatalog(
                    authored -> vaultInstalled && "vault".equals(authored)
                            ? Optional.of(new FakeIntegration(backend))
                            : Optional.empty(),
                    "materione:money");
        }

        @Override
        public MaterioneCurrencyCatalog currencies() {
            return currencies;
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
        public ProviderRegistration setPermissions(org.bukkit.plugin.Plugin owner, PermissionProvider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderRegistration setTransfer(org.bukkit.plugin.Plugin owner, ServerTransfer provider) {
            transfer = Optional.of(provider);
            return () -> transfer = Optional.empty();
        }
    }

    private static Currency gems() {
        return new Currency(NamespacedId.of("shop", "gems"), "Gems", "G$", 0, "vault", Map.of());
    }

    @Test
    void theProviderActionsDescribeThemselvesWithCategoryThreadingAndShorthand() {
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
        void giveAndTakeAcceptPositiveAmountsAndAnOptionalCurrencyAndRejectTheRest() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);
            var take = new TakeMoneyAction(providers);

            assertThat(give.parse(reader(params("amount", 100))).currency()).isNull();
            assertThat(give.parse(reader(params("amount", 2.5, "currency", "gems")))).isEqualTo(
                    new GiveMoneyAction.Params(2.5, "gems"));
            assertThat(take.parse(reader(params("amount", 100)))).isEqualTo(new TakeMoneyAction.Params(100, null));
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
        void giveAddsToTheDefaultCurrencyAndTakeRemovesFromIt() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);
            var take = new TakeMoneyAction(providers);

            assertThat(give.execute(give.parse(reader(params("amount", 150))), withPlayer())).isEqualTo(ActionOutcome.OK);
            assertThat(providers.backend.balances).containsEntry(ALEX, 150d);
            assertThat(take.execute(take.parse(reader(params("amount", 75))), withPlayer())).isEqualTo(ActionOutcome.OK);
            assertThat(providers.backend.balances).containsEntry(ALEX, 75d);
        }

        @Test
        void aNamedCurrencyIsUsedWhenTheStepNamesIt() {
            var providers = new FakeProviders(new FakePermissions());
            providers.currencies.define(List.of(gems()));
            providers.backend.balances.put(ALEX, 10d);
            var take = new TakeMoneyAction(providers);

            assertThat(take.execute(take.parse(reader(params("amount", 4, "currency", "gems"))), withPlayer()))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(providers.backend.balances).containsEntry(ALEX, 6d);
        }

        @Test
        void theStepRunsOnTheThreadOfTheCurrencyItNamesAndFallsBackToTheRegionForAnUnknownOne() {
            var providers = new FakeProviders(new FakePermissions());
            providers.currencies.define(List.of(gems()));
            var give = new GiveMoneyAction(providers);
            var take = new TakeMoneyAction(providers);

            // the default currency is Vault's (GLOBAL); a backend that touches one player's state is PLAYER_REGION
            assertThat(give.threading(give.parse(reader(params("amount", 1))))).isEqualTo(Threading.GLOBAL);
            providers.backend.threading = Threading.PLAYER_REGION;
            assertThat(give.threading(give.parse(reader(params("amount", 1))))).isEqualTo(Threading.PLAYER_REGION);
            assertThat(take.threading(take.parse(reader(params("amount", 1, "currency", "gems")))))
                    .isEqualTo(Threading.PLAYER_REGION);

            // an unknown currency, or one whose integration is not installed, hops to the region
            assertThat(give.threading(give.parse(reader(params("amount", 1, "currency", "nope")))))
                    .isEqualTo(Threading.PLAYER_REGION);
            providers.vaultInstalled = false;
            assertThat(give.threading(give.parse(reader(params("amount", 1))))).isEqualTo(Threading.PLAYER_REGION);
            // the headline stays the common case, for the vocabulary
            assertThat(give.threading()).isEqualTo(Threading.GLOBAL);
        }

        @Test
        void takeFailsWithInsufficientFundsAndTakesNothing() {
            var providers = new FakeProviders(new FakePermissions());
            providers.backend.balances.put(ALEX, 5d);
            var take = new TakeMoneyAction(providers);

            ActionOutcome outcome = take.execute(take.parse(reader(params("amount", 10))), withPlayer());

            assertThat(outcome).isEqualTo(ActionOutcome.failed("insufficient funds: balance 5.0, needs 10.0"));
            assertThat(providers.backend.balances).containsEntry(ALEX, 5d);
        }

        @Test
        void anUnknownCurrencyFailsTheStepByName() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);

            assertThat(give.execute(give.parse(reader(params("amount", 1, "currency", "nope"))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("unknown currency nope"));
        }

        @Test
        void aProviderFailureSurfacesItsReason() {
            var providers = new FakeProviders(new FakePermissions());
            providers.backend.refusal = "the bank is closed";
            var give = new GiveMoneyAction(providers);

            assertThat(give.execute(give.parse(reader(params("amount", 1))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed("the bank is closed"));
        }

        @Test
        void withoutTheIntegrationTheDefaultCurrencyIsUnavailableAndTheActionsSayWhy() {
            var providers = new FakeProviders(new FakePermissions());
            providers.vaultInstalled = false;
            var give = new GiveMoneyAction(providers);
            var take = new TakeMoneyAction(providers);

            assertThat(give.availability().available()).isFalse();
            assertThat(give.availability().reason()).contains("vault").contains("not installed");
            assertThat(take.availability().available()).isFalse();
            assertThat(give.execute(give.parse(reader(params("amount", 1))), withPlayer()))
                    .isEqualTo(ActionOutcome.failed(give.availability().reason()));
        }

        @Test
        void availabilityFollowsTheIntegrationInstalledAtTheMomentItIsAsked() {
            var providers = new FakeProviders(new FakePermissions());
            var give = new GiveMoneyAction(providers);
            providers.vaultInstalled = false;
            assertThat(give.availability().available()).isFalse();

            providers.vaultInstalled = true;
            assertThat(give.availability()).isEqualTo(Availability.AVAILABLE);

            providers.backend.up = false;
            assertThat(give.availability().reason()).contains("down");
        }

        @Test
        void aRunWithoutAPlayerFailsInsteadOfThrowing() {
            var take = new TakeMoneyAction(new FakeProviders(new FakePermissions()));

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
}
