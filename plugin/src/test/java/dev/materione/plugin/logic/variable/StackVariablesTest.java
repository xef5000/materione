package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.core.runtime.RuntimeVariables;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The item scope (logic.md section 38): the stack's own data, the thread rule, the no-stack run and the held-item readers. */
class StackVariablesTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final NamespacedId LEVEL = NamespacedId.of("rpg", "level");
    private static final NamespacedId TITLE = NamespacedId.of("rpg", "title");
    private static final NamespacedId SEALED = NamespacedId.of("rpg", "sealed");

    private static final VariableDeclaration LEVEL_DECL =
            new VariableDeclaration(LEVEL, VariableScope.ITEM, VariableType.NUMBER, 1, "Weapon level");
    private static final VariableDeclaration TITLE_DECL =
            new VariableDeclaration(TITLE, VariableScope.ITEM, VariableType.STRING, "", null);
    private static final VariableDeclaration SEALED_DECL =
            new VariableDeclaration(SEALED, VariableScope.ITEM, VariableType.BOOLEAN, false, null);

    /** A stack as the store sees it: data that behaves like Bukkit's, an identity, and whether it still exists. */
    static final class FakeStack implements StackPdc {
        final PersistentDataContainer data = VariableTestSupport.fakeContainer();
        String identity;
        boolean gone;

        FakeStack(String identity) {
            this.identity = identity;
        }

        @Override
        public Optional<PersistentDataContainer> view() {
            return gone ? Optional.empty() : Optional.of(data);
        }

        @Override
        public boolean edit(Consumer<PersistentDataContainer> edit) {
            if (gone) {
                return false;
            }
            edit.accept(data);
            return true;
        }

        @Override
        public String identity() {
            return identity;
        }

        StackRef ref(UUID owner) {
            return new BukkitStackRef(owner, this);
        }
    }

    private static RuntimeVariables declarations() {
        var map = new LinkedHashMap<NamespacedId, VariableDeclaration>();
        for (VariableDeclaration declaration : List.of(LEVEL_DECL, TITLE_DECL, SEALED_DECL)) {
            map.put(declaration.id(), declaration);
        }
        return new RuntimeVariables(map);
    }

    @Nested
    class Store {

        private final PdcStackVariableStore store = new PdcStackVariableStore(owner -> PdcVariableStore.Access.OWNED);

        @Test
        void eachTypeRoundTripsThroughTheStacksOwnData() {
            var sword = new FakeStack("rpg:sword");
            StackRef ref = sword.ref(STEVE);

            assertThat(store.write(LEVEL_DECL, ref, 3.0)).isTrue();
            assertThat(store.write(TITLE_DECL, ref, "Bloodfang")).isTrue();
            assertThat(store.write(SEALED_DECL, ref, true)).isTrue();

            assertThat(store.read(LEVEL_DECL, ref)).contains(3.0);
            assertThat(store.read(TITLE_DECL, ref)).contains("Bloodfang");
            assertThat(store.read(SEALED_DECL, ref)).contains(true);
        }

        @Test
        void theKeyIsTheSameNamespacedKeyThePlayerScopeUsesInAPlayersContainer() {
            var sword = new FakeStack("rpg:sword");
            store.write(LEVEL_DECL, sword.ref(STEVE), 3.0);

            assertThat(sword.data.has(PdcVariableStore.keyOf(LEVEL_DECL))).isTrue();
            assertThat(PdcVariableStore.keyOf(LEVEL_DECL).toString()).isEqualTo("materione:var.rpg.level");
        }

        @Test
        void twoStacksHoldTheirOwnValues() {
            var first = new FakeStack("rpg:sword");
            var second = new FakeStack("rpg:sword");

            store.write(LEVEL_DECL, first.ref(STEVE), 5.0);
            store.write(LEVEL_DECL, second.ref(STEVE), 2.0);

            assertThat(store.read(LEVEL_DECL, first.ref(STEVE))).contains(5.0);
            assertThat(store.read(LEVEL_DECL, second.ref(STEVE))).contains(2.0);
        }

        @Test
        void aStackWithDifferingStateCarriesDifferentDataSoItWillNotStackWithAnother() {
            // Minecraft merges two stacks only when their data components are equal; custom data is the PDC
            var plain = new FakeStack("rpg:coin");
            var upgraded = new FakeStack("rpg:coin");
            store.write(LEVEL_DECL, upgraded.ref(STEVE), 2.0);

            assertThat(snapshot(plain)).isNotEqualTo(snapshot(upgraded));
        }

        @Test
        void aValueEqualToTheDefaultClearsTheKeySoEqualStatesAreEqualBytes() {
            var plain = new FakeStack("rpg:coin");
            var raised = new FakeStack("rpg:coin");
            StackRef ref = raised.ref(STEVE);

            store.write(LEVEL_DECL, ref, 4.0);
            assertThat(snapshot(raised)).isNotEqualTo(snapshot(plain));
            store.write(LEVEL_DECL, ref, 1.0); // back to the default

            assertThat(raised.data.has(PdcVariableStore.keyOf(LEVEL_DECL))).isFalse();
            assertThat(snapshot(raised)).isEqualTo(snapshot(plain)); // a stack with state at default stacks again
            assertThat(store.read(LEVEL_DECL, ref)).isEmpty(); // absent: the caller takes the default
        }

        @Test
        void clearingAValueMakesItAbsent() {
            var sword = new FakeStack("rpg:sword");
            StackRef ref = sword.ref(STEVE);
            store.write(LEVEL_DECL, ref, 3.0);

            store.write(LEVEL_DECL, ref, null);

            assertThat(store.read(LEVEL_DECL, ref)).isEmpty();
        }

        @Test
        void aStackThatNoLongerExistsReadsAbsentAndRefusesWrites() {
            var sword = new FakeStack("rpg:sword");
            StackRef ref = sword.ref(STEVE);
            store.write(LEVEL_DECL, ref, 3.0);
            sword.gone = true;

            assertThat(store.read(LEVEL_DECL, ref)).isEmpty();
            assertThat(store.write(LEVEL_DECL, ref, 4.0)).isFalse();
        }

        @Test
        void aHandleWhoseObjectNowHoldsADifferentItemTouchesNothing() {
            var slot = new FakeStack("rpg:sword");
            StackRef ref = slot.ref(STEVE);
            store.write(LEVEL_DECL, ref, 3.0);
            slot.identity = "rpg:shield"; // the object was reused for another item while a run was parked

            assertThat(store.read(LEVEL_DECL, ref)).isEmpty();
            assertThat(store.write(LEVEL_DECL, ref, 9.0)).isFalse();
            assertThat(slot.data.get(PdcVariableStore.keyOf(LEVEL_DECL),
                    org.bukkit.persistence.PersistentDataType.DOUBLE)).isEqualTo(3.0);
        }

        @Test
        void aForeignHandleOrNoHandleHoldsNothing() {
            assertThat(store.read(LEVEL_DECL, null)).isEmpty();
            assertThat(store.write(LEVEL_DECL, null, 2.0)).isFalse();
            assertThat(store.read(LEVEL_DECL, () -> STEVE)).isEmpty();
        }

        @Test
        void theOwnersThreadDecidesAccess() {
            var sword = new FakeStack("rpg:sword");
            StackRef ref = sword.ref(STEVE);
            var offline = new PdcStackVariableStore(owner -> PdcVariableStore.Access.OFFLINE);
            var otherRegion = new PdcStackVariableStore(owner -> PdcVariableStore.Access.UNOWNED);

            assertThat(offline.read(LEVEL_DECL, ref)).isEmpty();
            assertThat(offline.write(LEVEL_DECL, ref, 2.0)).isFalse();
            assertThatThrownBy(() -> otherRegion.read(LEVEL_DECL, ref)).isInstanceOf(ReadUnavailableException.class);
            assertThat(otherRegion.write(LEVEL_DECL, ref, 2.0)).isFalse();
            assertThat(sword.data.isEmpty()).isTrue();
        }

        private Map<String, Object> snapshot(FakeStack stack) {
            var out = new HashMap<String, Object>();
            for (VariableDeclaration declaration : List.of(LEVEL_DECL, TITLE_DECL, SEALED_DECL)) {
                var key = PdcVariableStore.keyOf(declaration);
                if (stack.data.has(key)) {
                    out.put(key.toString(), stack.data.get(key, org.bukkit.persistence.PersistentDataType.DOUBLE));
                }
            }
            return out;
        }
    }

    @Nested
    class Run {

        @TempDir
        Path dir;

        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final AtomicLong now = new AtomicLong(1_000_000);
        private final Map<UUID, PersistentDataContainer> players = new HashMap<>();
        private final FakeStack mainHand = new FakeStack("rpg:sword");
        private boolean holding = true;

        private VariableService service() {
            return new VariableService(
                    new PdcVariableStore(uuid -> Optional.ofNullable(players.get(uuid))),
                    new ServerVariableStore(dir.resolve("variables.json"), warning -> { }),
                    new PdcStackVariableStore(owner -> PdcVariableStore.Access.OWNED),
                    player -> holding ? Optional.of(mainHand.ref(player)) : Optional.empty(),
                    StackVariablesTest::declarations, diagnostics::add, now::get);
        }

        @Test
        void aRunAboutAStackReadsAndWritesThatStack() {
            VariableService service = service();
            VariableBinding binding = service.bind(STEVE, mainHand.ref(STEVE));

            assertThat(binding.stored(LEVEL_DECL)).isEmpty(); // never written: the caller takes the default
            assertThat(binding.store(LEVEL_DECL, 3.0)).isTrue();

            assertThat(binding.stored(LEVEL_DECL)).contains(3.0);
            assertThat(mainHand.data.get(PdcVariableStore.keyOf(LEVEL_DECL),
                    org.bukkit.persistence.PersistentDataType.DOUBLE)).isEqualTo(3.0);
            assertThat(diagnostics).isEmpty();
        }

        @Test
        void twoRunsAboutTwoStacksNeverSeeEachOther() {
            VariableService service = service();
            var other = new FakeStack("rpg:sword");
            VariableBinding first = service.bind(STEVE, mainHand.ref(STEVE));
            VariableBinding second = service.bind(STEVE, other.ref(STEVE));

            first.store(LEVEL_DECL, 5.0);
            second.store(LEVEL_DECL, 2.0);

            assertThat(first.stored(LEVEL_DECL)).contains(5.0);
            assertThat(second.stored(LEVEL_DECL)).contains(2.0);
        }

        @Test
        void aRunWithNoStackReadsAbsentRefusesWritesAndReportsOncePerInterval() {
            VariableService service = service();
            VariableBinding binding = service.bind(STEVE); // a trigger that is not about an item

            assertThat(binding.stored(LEVEL_DECL)).isEmpty();
            assertThat(binding.stored(LEVEL_DECL)).isEmpty();
            assertThat(binding.store(LEVEL_DECL, 3.0)).isFalse();

            assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code().code()).isEqualTo("MTR-0065");
                assertThat(diagnostic.message()).contains("rpg:level").contains("no item stack");
            });

            now.addAndGet(VariableService.MISS_INTERVAL_MS - 1);
            binding.stored(LEVEL_DECL);
            assertThat(diagnostics).hasSize(1);
            now.addAndGet(1);
            binding.stored(LEVEL_DECL);
            assertThat(diagnostics).hasSize(2);
        }

        @Test
        void eachVariableHasItsOwnReportInterval() {
            VariableService service = service();
            VariableBinding binding = service.bind(STEVE);

            binding.stored(LEVEL_DECL);
            binding.stored(TITLE_DECL);

            assertThat(diagnostics).hasSize(2);
        }

        @Test
        void aServiceBuiltWithoutAnItemStoreReadsAbsentAndRefusesWrites() {
            var plain = new VariableService(
                    new PdcVariableStore(uuid -> Optional.ofNullable(players.get(uuid))),
                    new ServerVariableStore(dir.resolve("plain.json"), warning -> { }),
                    StackVariablesTest::declarations, diagnostics::add);
            VariableBinding binding = plain.bind(STEVE, mainHand.ref(STEVE));

            assertThat(binding.stored(LEVEL_DECL)).isEmpty();
            assertThat(binding.store(LEVEL_DECL, 2.0)).isFalse();
        }

        @Test
        void anAtomicMutationIsNotOfferedForAStack() {
            VariableService service = service();
            VariableBinding binding = service.bind(STEVE, mainHand.ref(STEVE));

            assertThat(binding.mutate(LEVEL_DECL, dev.materione.api.logic.variable.VariableOp.ADD, 1.0).ok()).isFalse();
        }

        // --- the API and the command read the held item ---------------------------------

        private ActorContext admin() {
            return ActorContext.player(STEVE, "Steve", permission -> true);
        }

        @Test
        void theApiReadsTheDefaultThenTheHeldStacksValue() {
            var api = service().api();

            assertThat(api.get(LEVEL, STEVE)).contains(1.0);
            assertThat(api.set(admin(), LEVEL, STEVE, 7.0).ok()).isTrue();
            assertThat(api.get(LEVEL, STEVE)).contains(7.0);
            assertThat(mainHand.data.has(PdcVariableStore.keyOf(LEVEL_DECL))).isTrue();
        }

        @Test
        void theApiWritesTheDefaultAsAClearedKey() {
            var api = service().api();
            api.set(admin(), LEVEL, STEVE, 7.0);

            assertThat(api.set(admin(), LEVEL, STEVE, 1.0).value()).isEqualTo(1.0);

            assertThat(mainHand.data.has(PdcVariableStore.keyOf(LEVEL_DECL))).isFalse();
        }

        @Test
        void anEmptyHandReadsTheDefaultAndRefusesAWrite() {
            holding = false;
            var api = service().api();

            assertThat(api.get(LEVEL, STEVE)).contains(1.0);
            assertThat(api.get(LEVEL, null)).contains(1.0);
            var refused = api.set(admin(), LEVEL, STEVE, 7.0);
            assertThat(refused.ok()).isFalse();
            assertThat(refused.diagnostics().get(0).code().code()).isEqualTo("MTR-0065");
            assertThat(api.set(admin(), LEVEL, null, 7.0).ok()).isFalse();
        }

        @Test
        void theApiChecksPermissionAndTypeBeforeTouchingTheStack() {
            var api = service().api();

            var denied = api.set(ActorContext.player(STEVE, "Steve", permission -> false), LEVEL, STEVE, 7.0);
            assertThat(denied.diagnostics().get(0).code().code()).isEqualTo("MTR-0055");
            var wrong = api.set(admin(), LEVEL, STEVE, "lots");
            assertThat(wrong.diagnostics().get(0).code().code()).isEqualTo("MTR-0064");
            assertThat(mainHand.data.isEmpty()).isTrue();
        }

        @Test
        void aHolderOnAnotherRegionIsUnavailableNotTheDefault() {
            var service = new VariableService(
                    new PdcVariableStore(uuid -> Optional.ofNullable(players.get(uuid))),
                    new ServerVariableStore(dir.resolve("other.json"), warning -> { }),
                    new PdcStackVariableStore(owner -> PdcVariableStore.Access.UNOWNED),
                    player -> {
                        throw new ReadUnavailableException("not on the player's region");
                    },
                    StackVariablesTest::declarations, diagnostics::add);

            var read = service.api().getResult(LEVEL, STEVE);
            assertThat(read.ok()).isFalse();
            assertThat(read.diagnostics().get(0).message()).contains("unavailable");
            assertThat(service.api().get(LEVEL, STEVE)).isEmpty();
        }
    }
}
