package dev.materione.plugin.escrow.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The crash gate (gui-input-durability.md §8, row H) is a test hook inside production code, so what these tests hold is
 * the part that matters to a production server: it is inert unless the JVM was started with the property, and it
 * only ever stops at the one name it is armed with. The kill itself is the harness's ({@code PaperEscrowCrashIT}).
 */
class CrashGateTest {

    @Test
    void aServerWithoutThePropertyHasNoGate() {
        assertThat(System.getProperty(CrashGate.PROPERTY)).as("the unit-test JVM is not started with the gate").isNull();
        assertThat(CrashGate.enabled()).isFalse();
        // returns at once, for every name the runtime uses
        for (String name : names()) {
            CrashGate.at(name);
        }
    }

    @Test
    void anArmedNameLogsTheMarkerOnceAndParks() {
        List<String> lines = new ArrayList<>();
        AtomicInteger parked = new AtomicInteger();
        CrashGate gate = new CrashGate(() -> "click-applied", lines::add, parked::incrementAndGet);

        gate.reach("account-written");
        gate.reach("release-done");
        assertThat(lines).isEmpty();
        assertThat(parked).hasValue(0);

        gate.reach("click-applied");
        assertThat(lines).containsExactly("MATERIONE-ESCROW-GATE click-applied");
        assertThat(parked).hasValue(1);
    }

    @Test
    void aValueThatNamesNoGateArmsNothing() {
        List<String> lines = new ArrayList<>();
        CrashGate gate = new CrashGate(() -> "off", lines::add, () -> {
            throw new AssertionError("parked");
        });
        for (String name : names()) {
            gate.reach(name);
        }
        assertThat(lines).isEmpty();
    }

    @Test
    void theArmedNameIsReadAtEveryReachSoTheHarnessCanReArmIt() {
        String[] armed = {"off"};
        AtomicInteger parked = new AtomicInteger();
        CrashGate gate = new CrashGate(() -> armed[0], line -> { }, parked::incrementAndGet);
        gate.reach("release-done");
        armed[0] = "release-done";
        gate.reach("release-done");
        assertThat(parked).hasValue(1);
    }

    @Test
    void everyGateNameIsDistinctAndNamedOnce() {
        List<String> names = names();
        assertThat(names).hasSizeGreaterThanOrEqualTo(7);
        Set<String> unique = new HashSet<>(names);
        assertThat(unique).hasSameSizeAs(names);
    }

    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Field field : CrashGate.Gates.class.getFields()) {
            try {
                names.add((String) field.get(null));
            } catch (IllegalAccessException impossible) {
                throw new AssertionError(impossible);
            }
        }
        return names;
    }
}
