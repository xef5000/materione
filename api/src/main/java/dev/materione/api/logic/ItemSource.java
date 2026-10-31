package dev.materione.api.logic;

/**
 * Where a GUI item layer's item comes from (logic.md §4.4): the SPI behind every {@code item:} a GUI
 * names. A source parses its authored parameters once, at load ({@link #parse}, pure), and resolves them
 * to an item for one viewer at render time ({@link #resolve}, on the viewer's thread).
 *
 * <p>The author writes a source in one of two ways. A plain string is the default source's payload
 * ({@code shop:gui/sword}, {@code minecraft:diamond}, {@code ${...}}); a one-key mapping names a source
 * ({@code head: {player: "${player.name}"}}, {@code player_slot: main_hand}). A source's
 * {@link ItemSourceDescription#shorthand() shorthand} is the key its scalar payload is read under, so
 * {@code player_slot: main_hand} reads as {@code {slot: main_hand}}.
 *
 * <p>Built-in sources register through {@link LogicApi#itemSources()} exactly as an addon does. An
 * addon's source is written with its qualified id ({@code example:weather_head}).
 *
 * @param <P> the source's typed, parsed parameters
 */
public interface ItemSource<P> {

    /** The local name an author writes; equals the registered id's value ({@code head}). */
    String id();

    /** The self-description: parameter schema, the shorthand, and the editor's view of the source. */
    ItemSourceDescription describe();

    /**
     * Parses the authored parameters. Pure and fail-fast: throw {@link IllegalArgumentException} for a
     * value the source cannot use, and the layer that holds it is reported and skipped.
     */
    P parse(ParamReader params);

    /**
     * Resolves the item for the viewer in {@code context}. A source that cannot produce an item returns
     * {@link ItemResolution#unresolved(String)} with the reason, which the GUI shows as a barrier item;
     * one that has nothing to show (an empty hand) returns {@link ItemResolution#none()}. A throw is
     * contained by the caller and counts as unresolved.
     */
    ItemResolution resolve(P params, LogicContext context);
}
