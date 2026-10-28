package dev.materione.core.document;

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
            List.of("items", "blocks", "furnitures", "recipes", "templates", "glyphs", "sounds", "paintings", "effects",
                    "guis");

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
            case "sounds" -> sound(name);
            case "paintings" -> painting(name);
            case "effects" -> effect(name);
            case "guis" -> gui(name);
            default -> throw new IllegalArgumentException(
                    "no scaffold for '" + dir + "' — create documents under "
                            + SCAFFOLD_TYPES);
        };
    }

    /**
     * The starter body of a new template that serves one type directory — a
     * single field that type declares, so the template is a valid partial
     * definition of it from the first save (a template needs at least one
     * field to be a mapping the editor can extend).
     */
    public static String templateFor(String serves, String id) {
        String name = sanitize(id);
        String body = switch (serves == null || serves.isBlank() ? "items" : serves) {
            case "items" -> "  material: PAPER\n";
            case "blocks" -> "  technique: note_block # note_block | string | chorus | technical | stairs | slab\n";
            default -> "  display_name: \"" + display(name) + "\"\n";
        };
        return "# Shared inheritance base: definitions pull this in via `template:`\n"
                + "# and override individual fields.\n"
                + name + ":\n" + body;
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

    /**
     * The New GUI starter: a three-row chest on the vanilla canvas (176 wide,
     * 114 + rows × 18 tall) with a raised panel and both slot regions, so the
     * first save already looks like a chest.
     */
    private static String gui(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  title: \"" + display(id) + "\"\n"
                + "  rows: 3\n"
                + "  layers:\n"
                + "    - id: frame\n"
                + "      type: panel\n"
                + "      x: 0\n"
                + "      y: 0\n"
                + "      width: 176\n"
                + "      height: 168\n"
                + "    - id: wells\n"
                + "      type: slots\n"
                + "      region: all\n";
    }

    private static String sound(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  sounds:\n"
                + "    - " + id + " # upload the .ogg to assets/sounds/\n"
                + "  # subtitle: \"" + display(id) + "\"\n"
                + "  # volume: 1.0\n"
                + "  # pitch: 1.0\n"
                + "  # jukebox: — turn this sound into a music-disc song\n"
                + "  #   description: \"" + display(id) + "\"\n"
                + "  #   duration: 90\n"
                + "  #   comparator_output: 7\n"
                + "  #   range: 1.0\n";
    }

    private static String painting(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  texture: painting/" + id + " # exactly 16 px per block\n"
                + "  width: 1\n"
                + "  height: 1\n"
                + "  # author: \"Unknown\"\n"
                + "  # title: \"" + display(id) + "\"\n"
                + "  # placeable: false — join the vanilla random-placement pool\n"
                + "  # Paintings apply at the next server restart (world datapack).\n";
    }

    private static String effect(String id) {
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  kind: rainbow # rainbow | wave | shake | pulse\n"
                + "  speed: 3.0\n"
                + "  # colors:\n"
                + "  #   - \"#FF0000\"\n"
                + "  #   - \"#0000FF\"\n"
                + "  # Compiles into the injected text-shader overlay (runtime-systems §6).\n"
                + "  # Use in text as <effect:" + id + ">…</effect>; test with\n"
                + "  # /materione dev effect " + id + " hello.\n";
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
