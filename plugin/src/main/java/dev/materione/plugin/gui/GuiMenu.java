package dev.materione.plugin.gui;

import dev.materione.api.gui.GuiContainer;
import dev.materione.api.NamespacedId;
import dev.materione.platform.text.MaterioneText;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Set;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * The window a live GUI opens: a {@link PluginGui} that knows the session it shows and the shape it
 * was created with, so a refresh can tell whether an open window still fits its GUI. It holds no
 * policy: a click inside it is handed to the {@link GuiInput} adapter, which routes it.
 *
 * <p>The title is parsed with {@link MaterioneText#parseData} so the glyph and text tags come through as
 * the compiler laid them out (gui.md §5, no emoji replacement).
 */
public final class GuiMenu extends PluginGui {

    private final GuiSession session;
    private final GuiContainer container;
    private String title;
    private final String baseTitle;
    private final GuiInput input;
    /** The input slots this window was shown with: the only slots whose items are the player's (logic.md §25). */
    private volatile Set<Integer> inputSlots = Set.of();

    public GuiMenu(GuiSession session, GuiContainer container, String title, GuiInput input) {
        this(session, container, title, title, input);
    }

    public GuiMenu(GuiSession session, GuiContainer container, String title, String baseTitle, GuiInput input) {
        super(container.open(), MaterioneText.parseData(title));
        this.session = session;
        this.container = container;
        this.title = title;
        this.baseTitle = baseTitle;
        this.input = input;
    }

    /** The input slots this window holds the player's items in, as it was last shown or rewritten. */
    public Set<Integer> inputSlots() {
        return inputSlots;
    }

    /** Records the input slots the window now shows (on open, and on every rewrite). */
    public void inputSlots(Set<Integer> slots) {
        this.inputSlots = Set.copyOf(slots);
    }

    /** The session this window shows. It outlives a re-open of the window (a title or size change). */
    public GuiSession session() {
        return session;
    }

    public NamespacedId guiId() {
        return session.gui();
    }

    public GuiContainer container() {
        return container;
    }

    /** The last successfully displayed MiniMessage title. */
    public String title() {
        return title;
    }

    public void title(String value) {
        this.title = value;
    }

    public String baseTitle() {
        return baseTitle;
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        input.click(this, event);
    }

    @Override
    protected void onOutsideClick(InventoryClickEvent event) {
        input.outside(this, event);
    }
}
