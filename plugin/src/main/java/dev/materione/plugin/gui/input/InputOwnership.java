package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.render.Rendered;
import java.util.Map;
import java.util.Set;

/** Display-time ownership policy projected from a render. */
public final class InputOwnership {

    private InputOwnership() {}

    public static Set<Integer> durable(RuntimeGuis.Live gui, Rendered rendered) {
        return gui.inputs().effective(gui.clicks(), rendered).all().stream()
                .filter(InputSlots.Input::durable).map(InputSlots.Input::slot)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static Set<Integer> slots(RuntimeGuis.Live gui, Rendered rendered) {
        return gui.inputs().effective(gui.clicks(), rendered).all().stream()
                .map(InputSlots.Input::slot).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static Map<Integer, InputSlots.Holder> holders(RuntimeGuis.Live gui, Rendered rendered) {
        return gui.inputs().effective(gui.clicks(), rendered).all().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        InputSlots.Input::slot, InputSlots.Input::holder));
    }
}
