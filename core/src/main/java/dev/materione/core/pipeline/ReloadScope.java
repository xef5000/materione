package dev.materione.core.pipeline;

import java.nio.file.Path;
import java.util.Set;

/**
 * What a pipeline run touches (plugin architecture §4.1). Scope resolution
 * expands this against the pack dependency graph and the resource dependency
 * graph before parsing; expansion is logged (MTR-0012), never silent.
 */
public sealed interface ReloadScope permits ReloadScope.Everything, ReloadScope.OnePack, ReloadScope.FileSet {

    /** Full reload: every pack, every file. */
    record Everything() implements ReloadScope {}

    /** One pack ({@code content/packs/<id>/}). */
    record OnePack(String packId) implements ReloadScope {}

    /** An explicit file set (targeted reload; the editor's save loop). */
    record FileSet(Set<Path> files) implements ReloadScope {}
}
