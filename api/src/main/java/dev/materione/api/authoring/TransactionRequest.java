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
 * @param origin where the list came from, for the audit: {@link #ORIGIN_EDITOR} (the default when null) or
 *     {@link #ORIGIN_CODE_MODE}
 * @param approval whether the owner granted this apply (codemode.md C10): {@link #APPROVAL_NOT_NEEDED} (the default
 *     when null) or {@link #APPROVAL_GRANTED}. The server records what the editor host asserts; it holds no grant
 * @param program the code-mode program text that produced the list, kept in the audit size-bounded
 *     ({@link #MAX_PROGRAM_CHARS}) with its digest (C13); null for a list that was not produced by a program
 */
public record TransactionRequest(
        String pack,
        List<TransactionOperation> operations,
        boolean dryRun,
        String origin,
        String approval,
        String program) {

    /** The audit origin of a list the editor sent as it is. */
    public static final String ORIGIN_EDITOR = "editor";

    /** The audit origin of a list a code-mode program staged. */
    public static final String ORIGIN_CODE_MODE = "code-mode";

    /** The apply needed no approval (it only created content, or changed what the session created). */
    public static final String APPROVAL_NOT_NEEDED = "not_needed";

    /** The owner granted the apply. */
    public static final String APPROVAL_GRANTED = "granted";

    /** The most characters of program text the audit keeps; the digest always covers the whole text. */
    public static final int MAX_PROGRAM_CHARS = 8192;

    /** The most operations one transaction accepts. */
    public static final int MAX_OPERATIONS = 200;

    /** The most distinct documents one transaction touches (decision C12), counting created, moved and deleted ones. */
    public static final int MAX_DOCUMENTS = 50;

    /** The most edits one operation carries. */
    public static final int MAX_EDITS_PER_OPERATION = 256;

    public TransactionRequest {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** A list with no audit context: origin {@code editor}, approval {@code not_needed}, no program. */
    public TransactionRequest(String pack, List<TransactionOperation> operations, boolean dryRun) {
        this(pack, operations, dryRun, null, null, null);
    }
}
