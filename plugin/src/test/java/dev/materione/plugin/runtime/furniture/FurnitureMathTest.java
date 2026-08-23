package dev.materione.plugin.runtime.furniture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeFurniture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure cluster math: snapping, rotation, cell rounding, lazy evolution timing. */
class FurnitureMathTest {

    private static RuntimeFurniture furniture(double snap, List<RuntimeFurniture.Stage> stages) {
        return new RuntimeFurniture(
                NamespacedId.of("sample", "chair"), "sample:chair_model", "Chair", snap,
                List.of(), List.of(), List.of(), 0, null, stages);
    }

    @Test
    void yawSnapsToTheDefinitionGrid() {
        RuntimeFurniture def = furniture(90, List.of());
        assertThat(FurnitureMath.snapYaw(def, 40f)).isEqualTo(0f);
        assertThat(FurnitureMath.snapYaw(def, 50f)).isEqualTo(90f);
        // half-up rounding convention: -45° snaps back to 0
        assertThat(FurnitureMath.snapYaw(def, -45f)).isZero();
        assertThat(FurnitureMath.snapYaw(def, -46f)).isEqualTo(-90f);
        // rotation disabled: always face the same way regardless of the placer
        assertThat(FurnitureMath.snapYaw(furniture(0, List.of()), 137f)).isZero();
        assertThat(FurnitureMath.snapYaw(furniture(45, List.of()), 50f)).isEqualTo(45f);
    }

    @Test
    void rotationMovesOffsetsAroundY() {
        // a quarter turn moves +x to -z (Minecraft yaw grows clockwise)
        double[] rotated = FurnitureMath.rotate(new double[] {1, 2, 0}, 90f);
        assertThat(rotated[0]).isCloseTo(0, within(1e-9));
        assertThat(rotated[2]).isCloseTo(-1, within(1e-9));
        double[] half = FurnitureMath.rotate(new double[] {1, 0, 0}, 180f);
        assertThat(half[0]).isCloseTo(-1, within(1e-9));
    }

    @Test
    void cellsRoundToWholeBlockPositions() {
        int[] cell = FurnitureMath.cell(10, 64, 10, new double[] {0.5, 1.0, -0.5}, 0f);
        // anchor center is (10.5, 64, 10.5); offset lands at (11, 65, 10)
        assertThat(cell).containsExactly(11, 65, 10);
        // the anchor's own center cell floors back to the anchor block
        int[] anchorCell = FurnitureMath.cell(-11, 60, -11, new double[] {0, 0, 0}, 0f);
        assertThat(anchorCell).containsExactly(-11, 60, -11);
    }

    /**
     * Evolution semantics: {@code after} is seconds since placement; the
     * furthest due stage wins so offline catch-up applies one hop, not a
     * replay of every intermediate stage.
     */
    @Test
    void dueStageIndexPicksTheFurthestDueStage() {
        RuntimeFurniture def = furniture(90, List.of(
                new RuntimeFurniture.Stage(NamespacedId.of("sample", "aged"), 10),
                new RuntimeFurniture.Stage(NamespacedId.of("sample", "ancient"), 30)));
        long placed = 1_000_000L;
        long now = placed + 5_000;
        assertThat(FurnitureMath.dueStageIndex(def, placed, now, 0)).isEqualTo(-1);
        now = placed + 12_000;
        assertThat(FurnitureMath.dueStageIndex(def, placed, now, 0)).isEqualTo(0);
        now = placed + 45_000;
        assertThat(FurnitureMath.dueStageIndex(def, placed, now, 0)).isEqualTo(1);
        // already applied: nothing more is due
        assertThat(FurnitureMath.dueStageIndex(def, placed, now, 1)).isEqualTo(1);
        assertThat(FurnitureMath.dueStageIndex(def, placed, now, 2)).isEqualTo(-1);
    }

    @Test
    void recordsPackAndUnpackLosslessly() {
        List<UUID> uuids = List.of(UUID.randomUUID(), UUID.randomUUID());
        assertThat(FurnitureMath.unpackUuids(FurnitureMath.packUuids(uuids))).isEqualTo(uuids);
        assertThat(FurnitureMath.unpackUuids("")).isEmpty();
        assertThat(FurnitureMath.unpackUuids("not-a-uuid")).isEmpty();

        List<int[]> cells = List.of(new int[] {1, -64, 3}, new int[] {-2, 70, 15});
        assertThat(FurnitureMath.unpackCells(FurnitureMath.packCells(cells)))
                .satisfies(parsed -> {
                    assertThat(parsed.get(0)).containsExactly(1, -64, 3);
                    assertThat(parsed.get(1)).containsExactly(-2, 70, 15);
                });
        // torn entries are skipped, never thrown
        assertThat(FurnitureMath.unpackCells("1,2,3;garbage;4")).hasSize(1);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
