package dev.materione.api.authoring;

import java.util.List;

/**
 * {@link AuthoringApi#transaction}: an ordered list of operations on the documents of <strong>one
 * pack</strong>, validated and applied as one unit (codemode.md section 5.4, decision C12). With
 * {@code dryRun} the list is applied in memory and checked, and nothing is written.
 *
 * @param pack the one pack every operation addresses
 * @param operations the operations, applied in order; at most {@value #MAX_OPERATIONS}
 * @param dryRun true to plan and validate only
 */
public record TransactionRequest(String pack, List<TransactionOperation> operations, boolean dryRun) {

    /** The most operations one transaction accepts. */
    public static final int MAX_OPERATIONS = 200;

    /** The most distinct documents one transaction touches (decision C12), counting created, moved and deleted ones. */
    public static final int MAX_DOCUMENTS = 50;

    /** The most edits one operation carries. */
    public static final int MAX_EDITS_PER_OPERATION = 256;

    public TransactionRequest {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
