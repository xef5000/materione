package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.graph.ContentGraph;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one pipeline run carries between stages (plugin architecture §4).
 * Stages read and write here; the runner owns the lifecycle. Later slices
 * add allocation results, staged output paths and the pre-apply report.
 */
public final class PipelineContext {

    private final ReloadScope scope;
    private final Path packsRoot;
    private final DiagnosticsBus diagnostics;
    private final QuarantineTracker quarantine;
    private final ContentGraph graph = new ContentGraph();
    private final List<ContentPack> packs = new ArrayList<>();
    /** Resource ids pulled out of the run by validation — invisible downstream. */
    private final List<NamespacedId> excluded = new ArrayList<>();
    /** Assets found under packs' {@code assets/} trees — recorded at parse so validators stay pure (R6). */
    private final Set<NamespacedId> knownAssets = new LinkedHashSet<>();
    /** The last completed run's packs — targeted scopes splice onto these. Set by the runner. */
    private volatile List<ContentPack> previousPacks = List.of();
    /** The persistent fingerprint table — targeted scopes expand on drift. Set by the runner. */
    private volatile FingerprintCache fingerprints;

    public PipelineContext(
            ReloadScope scope,
            Path packsRoot,
            DiagnosticsBus diagnostics,
            QuarantineTracker quarantine) {
        this.scope = scope;
        this.packsRoot = packsRoot;
        this.diagnostics = diagnostics;
        this.quarantine = quarantine;
    }

    void previousPacks(List<ContentPack> packs) {
        this.previousPacks = List.copyOf(packs);
    }

    void fingerprints(FingerprintCache cache) {
        this.fingerprints = cache;
    }

    public ReloadScope scope() {
        return scope;
    }

    public Path packsRoot() {
        return packsRoot;
    }

    public DiagnosticsBus diagnostics() {
        return diagnostics;
    }

    public QuarantineTracker quarantine() {
        return quarantine;
    }

    public ContentGraph graph() {
        return graph;
    }

    public List<ContentPack> packs() {
        return packs;
    }

    public List<NamespacedId> excluded() {
        return excluded;
    }

    public Set<NamespacedId> knownAssets() {
        return knownAssets;
    }

    public List<ContentPack> previousPacks() {
        return previousPacks;
    }

    public FingerprintCache fingerprints() {
        return fingerprints;
    }
}
