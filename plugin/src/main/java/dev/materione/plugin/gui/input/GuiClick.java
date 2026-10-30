package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;

/**
 * A click on a GUI's container, normalised from the Bukkit event (logic.md §9 rule 1): who, in which
 * session, on which slot, of which kind. The listener adapter builds it and hands it to the
 * {@link ClickRouter}; nothing downstream sees an {@code InventoryClickEvent}.
 *
 * @param key the hotbar key (1 to 9) of a {@link ClickKind#NUMBER_KEY} click, 0 otherwise
 */
public record GuiClick(Viewer viewer, GuiSession session, int slot, ClickKind kind, int key) {}
