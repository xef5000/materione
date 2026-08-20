package dev.materione.core.pipeline;

/**
 * What one pipeline run did, for the operator and {@code /materione doctor}.
 * Counters only — the diagnostics bus owns the whys.
 */
public record PipelineResult(
        int packsScanned,
        int filesParsed,
        int resourcesValid,
        int resourcesQuarantined,
        int graphEdges,
        boolean completed) {

    /** The run aborted before its stages finished (live state untouched, §8). */
    public static PipelineResult aborted(int packsScanned, int filesParsed) {
        return new PipelineResult(packsScanned, filesParsed, 0, 0, 0, false);
    }
}
