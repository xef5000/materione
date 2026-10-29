package dev.materione.content.gui;

import java.util.Set;

/**
 * The naming rules of a customized item (gui.md §2, §10 #2): the item lives under
 * {@code items/gui/<gui-path>/}, its id is the layer id when the pack has no resource of that
 * id, else prefixed with the GUI's name, else numbered. The API names the file after the id, and
 * an id is unique per pack, so a clash cannot keep the layer id. Pure: no I/O.
 */
final class GuiItemNaming {

    private static final int MIN_ID = 2;
    private static final int MAX_ID = 64;

    private GuiItemNaming() {}

    /** {@code guis/shop/main.yml} becomes {@code shop/main}. */
    static String guiStem(String guiPath) {
        return guiPath.replaceFirst("^guis/", "").replaceFirst("\\.ya?ml$", "");
    }

    /** The folder under {@code items/} that holds a GUI's customized items: {@code gui/shop/main}. */
    static String customFolder(String guiPath) {
        return "gui/" + guiStem(guiPath);
    }

    /** The pack-relative path of a customized item: {@code items/gui/shop/main/sword.yml}. */
    static String customPath(String guiPath, String id) {
        return "items/" + customFolder(guiPath) + "/" + id + ".yml";
    }

    /**
     * The item id for a layer. The layer id is preferred, then {@code <gui>_<layer>}, then the
     * same with a counter. Every candidate is 2 to 64 characters of {@code [a-z0-9_-]}.
     *
     * @throws IllegalArgumentException when no id fits (a 64-character block of taken names)
     */
    static String chooseItemId(String layerId, String guiPath, Set<String> taken) {
        String own = sanitize(layerId);
        String gui = sanitize(guiStem(guiPath).replace('/', '_'));
        String prefixed = gui + "_" + own;
        String preferred = own.length() >= MIN_ID ? own : prefixed;
        for (String candidate : new String[] {preferred, prefixed}) {
            if (fits(candidate) && !taken.contains(candidate)) {
                return candidate;
            }
        }
        String stem = prefixed.substring(0, Math.min(prefixed.length(), 60));
        for (int counter = 2; counter < 10_000; counter++) {
            String candidate = stem + "_" + counter;
            if (fits(candidate) && !taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no free item id for layer '" + layerId + "'");
    }

    /** Lower-case, every run of other characters as one underscore, no leading or trailing underscores. */
    static String sanitize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static boolean fits(String id) {
        return id.length() >= MIN_ID && id.length() <= MAX_ID && id.matches("[a-z0-9_-]+");
    }
}
