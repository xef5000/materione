package dev.materione.web.rpc.ops;

import java.util.List;
import java.util.Locale;

/**
 * Starter documents for {@code edit.create}, one per editable type
 * directory. Curated by hand on purpose: a scaffold is the first thing a new
 * user sees, so it teaches the common fields and leaves commented hints for
 * the rest — forms (generated from registry self-description) are where full
 * coverage lives. The plugin module pins every scaffold against the live
 * registry schemas with a test, so drift breaks CI rather than users.
 */
public final class Scaffolds {

    private Scaffolds() {}

    /** Type directories with a scaffold ({@code pack.yml} is createPack's). */
    public static final List<String> SCAFFOLD_TYPES =
            List.of("items", "blocks", "furnitures", "recipes", "templates", "glyphs");

    /**
     * @param path pack-relative path; the first segment picks the scaffold
     * @param id   resource id — defaults to the file's base name
     */
    public static String forPath(String path, String id) {
        String dir = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
        String name = sanitize(id != null && !id.isBlank() ? id : baseName(path));
        return switch (dir) {
            case "items" -> item(name);
            case "blocks" -> block(name);
            case "furnitures" -> furniture(name);
            case "recipes" -> recipe(name);
            case "templates" -> template(name);
            case "glyphs" -> glyph(name);
            default -> throw new IllegalArgumentException(
                    "no scaffold for '" + dir + "' — create documents under "
                            + SCAFFOLD_TYPES);
        };
    }

    // ------------------------------------------------------------------

    private static String item(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  material: STICK\n"
                + "  display_name: \"" + display(id) + "\"\n"
                + "  # texture: <path under assets/textures/> — pick one in the form\n"
                + "  # mechanics:\n"
                + "  #   - durability: {max: 500}\n";
    }

    private static String block(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  technique: note_block # note_block | string | chorus | technical | stairs | slab\n"
                + "  texture: block/" + id + "\n"
                + "  display_name: \"" + display(id) + "\"\n"
                + "  # item: <item-id> — optional custom placement item; one is generated automatically\n";
    }

    private static String furniture(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  texture: block/" + id + "\n"
                + "  display_name: \"" + display(id) + "\"\n"
                + "  rotation: 90\n"
                + "  hitboxes:\n"
                + "    - type: interaction\n"
                + "      offset: [0, 0, 0]\n"
                + "      width: 1.0\n"
                + "      height: 1.0\n"
                + "  # seats:\n"
                + "  #   - offset: [0, 0.3, 0]\n"
                + "  # item: <item-id> — optional custom placement item; one is generated automatically\n";
    }

    private static String recipe(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  type: shaped # shaped | shapeless | furnace\n"
                + "  result: " + id + "\n"
                + "  shape:\n"
                + "    - \" A \"\n"
                + "    - \" B \"\n"
                + "    - \" A \"\n"
                + "  ingredients:\n"
                + "    A: minecraft:stone\n"
                + "    B: minecraft:stick\n";
    }

    private static String template(String id) {
        return "# Shared inheritance base: definitions pull this in via `template:`\n"
                + "# and override individual fields.\n"
                + id + ":\n"
                + "  material: PAPER\n";
    }

    private static String glyph(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  texture: gui/" + id + "\n"
                + "  ascent: 8\n"
                + "  height: 8\n"
                + "  # placeholders:\n"
                + "  #   - \":" + id + ":\"\n"
                + "  # emoji: true — chat interception + tab-completion\n"
                + "  # The codepoint is allocated by the server and shown in the preview.\n";
    }

    // ------------------------------------------------------------------

    private static String baseName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Ids become YAML keys — keep them conservative regardless of caller. */
    private static String sanitize(String raw) {
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!cleaned.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("resource id must be 2..64 chars of a-z 0-9 _ -: " + raw);
        }
        return cleaned;
    }

    private static String display(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
