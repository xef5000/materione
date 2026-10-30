package dev.materione.core.pipeline;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.SourceLocation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duplicate resource ids inside one pack (plugin architecture §4.2, #194). Two documents that
 * declare the same id for the same content type cannot both own it, so one definition is kept and
 * the other is dropped before validation, graph build and compile. The rule is fixed and does not
 * depend on the order the files were read in:
 *
 * <ul>
 *   <li>the declaration whose document comes first in pack-relative path order (plain string
 *       order, forward slashes) keeps the id;</li>
 *   <li>every other declaration is excluded and reported once, with {@link DiagnosticCodes#DUPLICATE_RESOURCE_ID},
 *       naming both files. It is not put in the quarantine tracker: quarantine is keyed by id, so
 *       quarantining the loser would also hide the winner that owns the id.</li>
 * </ul>
 *
 * <p>Ids are unique per content type, not per pack: a block and an item may share an id (the
 * placement rule, runtime-systems §13 covers that pair), and the same id in two packs is two
 * namespaced resources. Only the pure function lives here; the parse stage supplies the resources
 * of a whole pack, so a targeted reload resolves a pack exactly as a full run does.
 */
public final class DuplicateResources {

    /** The resources that survive, in their original order, and the diagnostics for those dropped. */
    public record Resolution(List<ContentResource> kept, List<Diagnostic> diagnostics) {

        public Resolution {
            kept = List.copyOf(kept);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private DuplicateResources() {}

    /**
     * Resolves duplicates among one pack's resources.
     *
     * @param packDir the pack directory; source files are named relative to it
     * @param resources every resource the pack parsed, in any order
     */
    public static Resolution resolve(Path packDir, List<ContentResource> resources) {
        Path root = packDir.toAbsolutePath().normalize();
        // winners: the first resource per (type, id) in path order
        List<ContentResource> byPath = new ArrayList<>(resources);
        byPath.sort(Comparator.comparing((ContentResource r) -> relative(root, r.sourceFile()))
                .thenComparing(r -> r.contentType())
                .thenComparing(r -> r.id().toString()));
        Map<String, ContentResource> winners = new HashMap<>();
        Set<ContentResource> losers = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ContentResource resource : byPath) {
            String key = resource.contentType() + "|" + resource.id();
            ContentResource winner = winners.putIfAbsent(key, resource);
            if (winner != null) {
                losers.add(resource);
                diagnostics.add(diagnostic(root, winner, resource));
            }
        }
        List<ContentResource> kept = new ArrayList<>();
        for (ContentResource resource : resources) {
            if (!losers.contains(resource)) {
                kept.add(resource);
            }
        }
        return new Resolution(kept, diagnostics);
    }

    private static Diagnostic diagnostic(Path root, ContentResource winner, ContentResource loser) {
        String keep = relative(root, winner.sourceFile());
        String drop = relative(root, loser.sourceFile());
        return new Diagnostic(
                DiagnosticCodes.DUPLICATE_RESOURCE_ID, Severity.WARN,
                "resource " + loser.id() + " (" + loser.contentType() + ") is declared in both "
                        + keep + " and " + drop + "; " + keep + " is used and " + drop
                        + "'s declaration is ignored",
                loser.id(),
                new SourceLocation(loser.sourceFile().toAbsolutePath().normalize().toString(), 1, 1),
                "rename one of the declarations, or move it to another pack; the first file in path order keeps the id");
    }

    /** The pack-relative, forward-slash path: the one ordering the rule uses on every platform. */
    static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
