package dev.materione.plugin.gui.prompt;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * {@code materione:sign} (logic.md §6.6, §29): reserved for a client-only sign editor, and unavailable on this build.
 *
 * <p>The method is registered so the studio can show it and say why it cannot run. It never touches the world. The
 * first design placed a real sign block for the editor and restored it afterwards; a crash between the two left a
 * sign in the world that other players could see, so that design is gone. A client-only editor needs Paper's
 * {@code Player#openVirtualSign(Position, Side)}, which exists only in the 26.x API that this build does not compile
 * against (the 1.21.4 floor has no client-only sign editor: its sign editor needs a real server-side sign). Until a
 * 26.x path lands behind the modern seam, {@link #availability} is unavailable and a prompt refuses to ask with this
 * reason ({@code MTR-0084}). Chat and anvil answer every question that sign would have.
 */
public final class SignInput implements InputMethod {

    /** The name an author writes. */
    public static final String ID = "sign";

    /** Why the method cannot run on this build, as the studio and the player see it. */
    static final String UNAVAILABLE_REASON = "the client-only sign editor needs Paper's Player#openVirtualSign, "
            + "which this build's 1.21.4 API does not have; use chat or anvil";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public InputMethodDescription describe() {
        return InputMethodDescription.of(new SelfDescription("Sign",
                "The player writes the answer on a sign editor (unavailable until a client-only editor is wired)",
                List.of(), "1.0.0"), true);
    }

    @Override
    public Availability availability() {
        return Availability.unavailable(UNAVAILABLE_REASON);
    }

    /** Never reached through a prompt, which refuses an unavailable method first; throws for a direct caller. */
    @Override
    public void ask(Player player, String prompt, Reply reply) {
        throw new IllegalStateException(UNAVAILABLE_REASON);
    }

    /** Nothing was opened, so there is nothing to undo, and the world is never touched. */
    @Override
    public void dismiss(Player player) {
        // no state, no block: a sign prompt cannot open anything on this build
    }
}
