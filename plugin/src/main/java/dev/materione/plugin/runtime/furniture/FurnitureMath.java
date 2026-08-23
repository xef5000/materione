package dev.materione.plugin.runtime.furniture;

import dev.materione.core.runtime.RuntimeFurniture;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure geometry and lifecycle math for furniture clusters (runtime-systems
 * §5): rotated offsets, spawn positions, evolution scheduling. Extracted
 * from the Bukkit listener so it stays unit-testable — and so the hot
 * questions ("does this yaw snap?", "which stage is due?") never depend on
 * a live server.
 */
public final class FurnitureMath {

    private FurnitureMath() {}

    /** The snapped yaw for {@code playerYaw}, or 0 when the definition disables rotation. */
    public static float snapYaw(RuntimeFurniture def, float playerYaw) {
        if (def.rotationSnap() == 0) {
            return 0f;
        }
        double snap = def.rotationSnap();
        return (float) (Math.round(playerYaw / snap) * snap);
    }

    /**
     * Rotates an anchor-relative offset by {@code yawDegrees} around Y.
     * Barrier/light cells land on whole block positions afterwards; seats
     * and interaction centers keep sub-block precision.
     */
    public static double[] rotate(double[] offset, float yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        // Minecraft yaw: 0 faces +Z; rotating by yaw moves x/z accordingly
        double x = offset[0] * cos + offset[2] * sin;
        double z = -offset[0] * sin + offset[2] * cos;
        return new double[] {x, offset[1], z};
    }

    /**
     * The world position of a seat: anchor center + rotated offset. Seat Y
     * sits at mount height minus the passenger's seated sink handled by the
     * client — no correction needed for plain-entity mounts.
     */
    public static double[] seatPosition(
            int anchorX, int anchorY, int anchorZ, double[] seatOffset, float yawDegrees) {
        double[] rotated = rotate(seatOffset, yawDegrees);
        return new double[] {
                anchorX + 0.5 + rotated[0], anchorY + rotated[1], anchorZ + 0.5 + rotated[2]};
    }

    /**
     * The whole block cell a barrier/light hitbox occupies: anchor + rotated
     * offset rounded to the containing cell.
     */
    public static int[] cell(int anchorX, int anchorY, int anchorZ, double[] offset, float yawDegrees) {
        double[] rotated = rotate(offset, yawDegrees);
        return new int[] {
                (int) Math.floor(anchorX + 0.5 + rotated[0]),
                (int) Math.floor(anchorY + rotated[1]),
                (int) Math.floor(anchorZ + 0.5 + rotated[2])};
    }

    /**
     * The highest evolution stage index beyond {@code appliedStages} whose
     * activation time ({@code after}, in seconds since placement) has passed
     * at {@code nowMillis} — lazy evaluation from absolute timestamps, never
     * a scan timer. Returns -1 when nothing is due.
     */
    public static int dueStageIndex(
            RuntimeFurniture def, long placedMillis, long nowMillis, int appliedStages) {
        long elapsedSeconds = (nowMillis - placedMillis) / 1000L;
        int due = -1;
        List<RuntimeFurniture.Stage> stages = def.evolution();
        for (int i = appliedStages; i < stages.size(); i++) {
            if (elapsedSeconds >= stages.get(i).afterSeconds()) {
                due = i;
            }
        }
        return due;
    }

    /** Compact member-uuid serialization for the root PDC record. */
    public static String packUuids(List<java.util.UUID> uuids) {
        var out = new StringBuilder();
        for (java.util.UUID uuid : uuids) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(uuid);
        }
        return out.toString();
    }

    public static List<java.util.UUID> unpackUuids(String packed) {
        var out = new ArrayList<java.util.UUID>();
        if (packed == null || packed.isBlank()) {
            return out;
        }
        for (String part : packed.split(",")) {
            try {
                out.add(java.util.UUID.fromString(part.trim()));
            } catch (IllegalArgumentException e) {
                // torn record: the replay treats this member as missing
            }
        }
        return out;
    }

    /** "x,y,z;x,y,z" absolute cell packing for barrier/light records. */
    public static String packCells(List<int[]> cells) {
        var out = new StringBuilder();
        for (int[] cell : cells) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(cell[0]).append(',').append(cell[1]).append(',').append(cell[2]);
        }
        return out.toString();
    }

    public static List<int[]> unpackCells(String packed) {
        var out = new ArrayList<int[]>();
        if (packed == null || packed.isBlank()) {
            return out;
        }
        for (String part : packed.split(";")) {
            String[] xyz = part.trim().split(",");
            if (xyz.length != 3) {
                continue;
            }
            try {
                out.add(new int[] {Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim())});
            } catch (NumberFormatException e) {
                // skip torn entries
            }
        }
        return out;
    }

    /** Lowercase material-safe host key style helper shared with callers. */
    public static String lowercase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
