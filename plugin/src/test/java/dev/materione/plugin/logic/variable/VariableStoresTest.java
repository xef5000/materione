package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.plugin.logic.variable.VariableTestSupport.Wired;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two stores, the restart behaviour of each, and the actor-checked API (logic.md §3.1, §3.3). */
class VariableStoresTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dir;

    private static ActorContext actor(Predicate<String> permissions) {
        return ActorContext.player(STEVE, "Steve", permissions);
    }

    @Nested
    class PlayerContainer {

        @Test
        void eachTypeRoundTripsThroughTheContainer() {
            PersistentDataContainer container = VariableTestSupport.fakeContainer();
            var store = new PdcVariableStore(uuid -> Optional.of(container));

            assertThat(store.write(VariableTestSupport.COINS_DECL, STEVE, 12.5)).isTrue();
            assertThat(store.write(VariableTestSupport.CLAIMED_DECL, STEVE, true)).isTrue();
            assertThat(store.read(VariableTestSupport.COINS_DECL, STEVE)).contains(12.5);
            assertThat(store.read(VariableTestSupport.CLAIMED_DECL, STEVE)).contains(true);
        }

        @Test
        void clearingAValueMakesItAbsent() {
            PersistentDataContainer container = VariableTestSupport.fakeContainer();
            var store = new PdcVariableStore(uuid -> Optional.of(container));
            store.write(VariableTestSupport.COINS_DECL, STEVE, 3.0);

            store.write(VariableTestSupport.COINS_DECL, STEVE, null);

            assertThat(store.read(VariableTestSupport.COINS_DECL, STEVE)).isEmpty();
        }

        @Test
        void anOfflinePlayerCannotBeReadOrWritten() {
            var store = new PdcVariableStore(uuid -> Optional.empty());

            assertThat(store.write(VariableTestSupport.COINS_DECL, STEVE, 1.0)).isFalse();
            assertThat(store.read(VariableTestSupport.COINS_DECL, STEVE)).isEmpty();
            assertThat(store.write(VariableTestSupport.COINS_DECL, null, 1.0)).isFalse();
        }

        @Test
        void theKeyIsNamespacedByPackAndKey() {
            assertThat(PdcVariableStore.keyOf(VariableTestSupport.COINS_DECL).toString())
                    .isEqualTo("materione:var.shop.coins");
        }
    }

    @Nested
    class ServerFile {

        @Test
        void aValueSurvivesARestart() throws Exception {
            Path file = dir.resolve("storage").resolve("variables.json");
            var first = new ServerVariableStore(file, warning -> { });
            first.write(VariableTestSupport.SEASON_DECL, null, "winter");
            first.write(VariableTestSupport.SEASON_DECL, null, "summer");
            assertThat(first.isDirty()).isTrue();
            first.flush();
            assertThat(first.isDirty()).isFalse();

            var restarted = new ServerVariableStore(file, warning -> { });

            assertThat(restarted.read(VariableTestSupport.SEASON_DECL, null)).contains("summer");
        }

        @Test
        void theFileIsWrittenAtomicallyAndNoTemporaryIsLeftBehind() throws Exception {
            Path file = dir.resolve("variables.json");
            var store = new ServerVariableStore(file, warning -> { });
            store.write(VariableTestSupport.SEASON_DECL, null, "autumn");

            store.flush();

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("\"shop:season\"", "autumn");
            assertThat(Files.exists(dir.resolve("variables.json.tmp"))).isFalse();
        }

        @Test
        void aFlushWithNoChangeWritesNothing() throws Exception {
            Path file = dir.resolve("variables.json");
            var store = new ServerVariableStore(file, warning -> { });

            store.flush();

            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        void aCorruptFileIsSetAsideAndTheStoreStartsEmpty() throws Exception {
            Path file = dir.resolve("variables.json");
            Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
            var warnings = new ArrayList<String>();

            var store = new ServerVariableStore(file, warnings::add);

            assertThat(store.read(VariableTestSupport.SEASON_DECL, null)).isEmpty();
            assertThat(Files.exists(dir.resolve("variables.json.corrupt"))).isTrue();
            assertThat(warnings).singleElement().asString().contains("unreadable");
        }

        @Test
        void aValueOfTheWrongTypeIsRefused() {
            var store = new ServerVariableStore(dir.resolve("variables.json"), warning -> { });

            assertThat(store.write(VariableTestSupport.COINS_DECL, null, "not a number")).isFalse();
            assertThat(store.write(VariableTestSupport.SEASON_DECL, null, "ok")).isTrue();
        }
    }

    @Nested
    class Service {

        @Test
        void aNeverWrittenVariableReadsItsDefault() {
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());

            assertThat(wired.service().api().get(VariableTestSupport.SEASON, null)).contains("spring");
            assertThat(wired.service().api().get(VariableTestSupport.COINS, STEVE)).contains(0.0);
        }

        @Test
        void anUndeclaredVariableIsAbsent() {
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());

            assertThat(wired.service().api().get(NamespacedId.of("shop", "gems"), STEVE)).isEmpty();
        }

        @Test
        void aWriteNeedsTheMaterioneVarNode() {
            Wired wired = VariableTestSupport.wired(dir, containersFor(STEVE));

            var result = wired.service().api().set(actor(node -> false), VariableTestSupport.COINS, STEVE, 5.0);

            assertThat(result.ok()).isFalse();
            assertThat(result.diagnostics()).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.PERMISSION_DENIED));
        }

        @Test
        void aWriteWithThePermissionIsStoredAndReturnsTheValue() {
            Wired wired = VariableTestSupport.wired(dir, containersFor(STEVE));

            var result = wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), VariableTestSupport.COINS, STEVE, "9");

            assertThat(result.ok()).isTrue();
            assertThat(result.value()).isEqualTo(9.0);
            assertThat(wired.service().api().get(VariableTestSupport.COINS, STEVE)).contains(9.0);
        }

        @Test
        void aWriteOfTheWrongTypeIsRefusedWithItsCode() {
            Wired wired = VariableTestSupport.wired(dir, containersFor(STEVE));

            var result = wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), VariableTestSupport.COINS, STEVE, "lots");

            assertThat(result.ok()).isFalse();
            assertThat(result.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
        }

        @Test
        void anUndeclaredWriteIsRefusedWithItsCode() {
            Wired wired = VariableTestSupport.wired(dir, containersFor(STEVE));

            var result = wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), NamespacedId.of("shop", "gems"), STEVE, 1.0);

            assertThat(result.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
        }

        @Test
        void aPlayerWriteForAnOfflinePlayerIsRefusedWithItsCode() {
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());

            var result = wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), VariableTestSupport.COINS, STEVE, 1.0);

            assertThat(result.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.VARIABLE_STORE_FAILED);
        }

        @Test
        void aServerWriteIgnoresThePlayerAndReadsBackAnywhere() {
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());

            wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), VariableTestSupport.SEASON, null, "fall");

            assertThat(wired.service().api().get(VariableTestSupport.SEASON, null)).contains("fall");
        }

        @Test
        void aNullWriteResetsToTheDefault() {
            Wired wired = VariableTestSupport.wired(dir, containersFor(STEVE));
            wired.service().api().set(actor(VariableService.PERMISSION::equals), VariableTestSupport.COINS, STEVE, 4.0);

            var result = wired.service().api().set(
                    actor(VariableService.PERMISSION::equals), VariableTestSupport.COINS, STEVE, null);

            assertThat(result.ok()).isTrue();
            assertThat(result.value()).isEqualTo(0.0);
            assertThat(wired.service().api().get(VariableTestSupport.COINS, STEVE)).contains(0.0);
        }

        @Test
        void theDebouncedFlushWritesWhatChangedAndCloseWritesTheRest() throws Exception {
            Path file = dir.resolve("variables.json");
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());
            wired.service().api().set(actor(VariableService.PERMISSION::equals), VariableTestSupport.SEASON, null, "dusk");
            var scheduled = new ArrayList<Runnable>();

            wired.service().autoFlush(scheduled::add);
            assertThat(scheduled).hasSize(1);
            scheduled.get(0).run(); // one tick: flushes, and schedules the next

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("dusk");
            assertThat(scheduled).hasSize(2);

            wired.service().api().set(actor(VariableService.PERMISSION::equals), VariableTestSupport.SEASON, null, "dawn");
            wired.service().close();

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("dawn");
        }

        @Test
        void aGuiSessionPortIsEmptyUntilTheGuiRuntimeRegistersOne() {
            Wired wired = VariableTestSupport.wired(dir, new HashMap<>());

            assertThat(wired.service().guiSessions()).isSameAs(GuiSessionPort.NONE);
            wired.service().guiSessions(null);
            assertThat(wired.service().guiSessions()).isSameAs(GuiSessionPort.NONE);
        }

        @Test
        void aPlayerVariableBindingReadsItsOwnHolder() {
            Map<UUID, PersistentDataContainer> containers = containersFor(STEVE);
            Wired wired = VariableTestSupport.wired(dir, containers);
            var binding = wired.service().bind(STEVE);

            assertThat(binding.store(VariableTestSupport.COINS_DECL, 2.0)).isTrue();
            assertThat(binding.stored(VariableTestSupport.COINS_DECL)).contains(2.0);
            assertThat(wired.service().bind(null).store(VariableTestSupport.COINS_DECL, 1.0)).isFalse();
        }
    }

    private static Map<UUID, PersistentDataContainer> containersFor(UUID player) {
        Map<UUID, PersistentDataContainer> containers = new HashMap<>();
        containers.put(player, VariableTestSupport.fakeContainer());
        return containers;
    }
}
