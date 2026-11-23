package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.Optional;
import java.util.Set;

/**
 * The internal view-kind contract (views.md §3.2, decision D-13): what the container GUI, the dialog (#6) and the
 * HUD (#7) each implement so that one verb set ({@code view.open}, {@code view.close}, {@code view.back}) and one
 * registry serve them all. It is not a public addon API in v1; the facet that describes a kind is public through
 * the content type's self-description ({@link dev.materione.api.content.ContentType#view()}).
 *
 * <p>A kind is registered with {@link ViewKinds}. The members, by the table of views.md §3.2:
 *
 * <table>
 *   <caption>The contract</caption>
 *   <tr><td>{@link #contentType()}</td><td>the registered type it renders</td></tr>
 *   <tr><td>{@link #facet()}</td><td>{@code viewerRule}, {@code rerenderDefault}, {@code supportsTarget},
 *       {@code supportsKey}: the same facet the type's self-description publishes</td></tr>
 *   <tr><td>{@link #reads}</td><td>the compile-time read set (row V1; empty until then)</td></tr>
 *   <tr><td>{@link #render}</td><td>render the viewer's current frame and deliver what changed (the kind splits
 *       it into {@code render} and {@code deliver} when row V1 needs the split)</td></tr>
 *   <tr><td>{@link #minInterval()}</td><td>fewest ticks between two automatic renders of one viewer</td></tr>
 *   <tr><td>{@link #open}, {@link #close}</td><td>attach and detach a viewer of an {@code opened} kind</td></tr>
 * </table>
 *
 * <p>Every method runs on the viewer's owner thread (D8); nothing here is thread-safe on its own.
 */
public interface ViewKind {

    /** The content type this kind renders ({@code gui}). */
    String contentType();

    /** The kind's view facet. */
    ViewFacet facet();

    /** Fewest ticks between two automatic renders of one viewer (views.md §3.2); the GUI's is 1. */
    int minInterval();

    /**
     * The state keys the definition reads, extracted at compile time (views.md §5.2). Row V1 builds the extraction;
     * until then a kind answers empty, which means "nothing is indexed" and changes no behaviour.
     */
    default Set<String> reads(NamespacedId definition) {
        return Set.of();
    }

    /**
     * Resolves what an author wrote after {@code view.open}: a qualified id ({@code shop:menu}) is taken as written; a
     * bare name means the definition of that name in {@code near}'s pack when there is one, else the only live
     * definition of that name. More than one candidate, or none, is empty: nothing is guessed.
     *
     * @param near the definition the reference was written in (its pack is preferred), or null
     * @return the live definition's id, or empty when this kind has none matching
     */
    Optional<NamespacedId> resolve(String written, NamespacedId near);

    /**
     * Opens a view of this kind through the kind's opener and the shared {@code OpenGate}, replacing the viewer's
     * record of this kind. The one way in: the command, the runtime API, the editor preview, {@code view.open} and
     * {@code view.back} all build an {@link OpenRequest}.
     */
    OpenResult open(OpenRequest request);

    /**
     * A flow closes the viewer's record ({@code view.close}): ends it with {@link CloseReason#ACTION}, fires the
     * kind's close trigger and closes the window.
     *
     * @return false when the record had already ended (nothing to close)
     */
    boolean close(Viewer viewer, ViewerRecord record);

    /**
     * Renders the viewer's current frame now and delivers what changed.
     *
     * @return false when the record is stale or its definition is gone (nothing rendered)
     */
    boolean render(Viewer viewer, ViewerRecord record);
}
