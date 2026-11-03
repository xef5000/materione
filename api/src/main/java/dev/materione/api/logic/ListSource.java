package dev.materione.api.logic;

import java.util.List;
import java.util.Map;

/**
 * Where a GUI {@code list} component's entries come from (logic.md §4.5, §6.7): the SPI behind every
 * {@code source:} a list names. A source parses its authored parameters once, at load ({@link #parse},
 * pure), and returns the entries for one viewer when the window renders ({@link #entries}, on the
 * viewer's thread).
 *
 * <p>An entry is a flat map of its fields, the variables a rule reads as {@code entry.<field>}. The
 * fields a source produces are declared in its {@link ListSourceDescription#entry() entry schema}, so
 * the editor can offer them; a field a source does not declare reads as absent. Field values are text,
 * whole or decimal numbers, booleans or {@code null}.
 *
 * <p>The author writes a source in one of two ways, as for item sources (§4.4): a plain string names the
 * source ({@code online_players}), and a mapping names it with its parameters
 * ({@code {type: pack_items, pack: shop}}). A source's {@link ListSourceDescription#shorthand() shorthand}
 * is the parameter a scalar is read under.
 *
 * <p>Built-in sources register through {@link LogicApi#listSources()} exactly as an addon does; an addon's
 * source is written with its qualified id ({@code example:weather_list}).
 *
 * @param <P> the source's typed, parsed parameters
 */
public interface ListSource<P> {

    /** The local name an author writes; equals the registered id's value ({@code online_players}). */
    String id();

    /** The self-description: parameter schema, shorthand, entry schema and whether the entries are cached. */
    ListSourceDescription describe();

    /**
     * Parses the authored parameters. Pure and fail-fast: throw {@link IllegalArgumentException} for a value
     * the source cannot use, and the list that holds it is reported and left out.
     */
    P parse(ParamReader params);

    /**
     * The entries for the viewer in {@code context}, before the list filters and sorts them. A throw is
     * contained by the caller: the list shows no entries and the failure is reported (MTR-0083).
     *
     * @return the entries in the source's own order; never null
     */
    List<Map<String, Object>> entries(P params, LogicContext context);
}
