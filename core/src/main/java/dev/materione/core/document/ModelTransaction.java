package dev.materione.core.document;

import java.util.List;
import java.util.Objects;

/**
 * One atomic Blockbench save-back as the model transaction sees it (the
 * editor's {@code authoring.saveModel} decoded): everything the transaction
 * stages on disk plus the owner-document patch, applied all-or-nothing by
 * {@link ModelTransactionService}.
 *
 * <p>Blank-able strings ({@code ownerPath}, {@code ownerType},
 * {@code sourcePath}) follow the editor wire's rules: blank means "not
 * present". {@code ownerPath} and {@code ownerField} name the existing field
 * the model reference is written into when attaching;
 * {@code createOwner} scaffolds the owner instead ({@code ownerId} +
 * {@code ownerType} + {@code ownerName} then apply).
 *
 * @param pack             pack id the transaction writes into
 * @param ownerPath        existing owner document (pack-relative); blank with createOwner
 * @param createOwner      scaffold the owner document instead of attaching
 * @param ownerId          new owner resource id (createOwner only)
 * @param ownerType        owner content type directory; blank defaults to {@code items}
 * @param ownerName        display name applied to a scaffolded owner
 * @param modelReference   the model id written into the owner field
 * @param ownerField       field path inside the owner document (attach only)
 * @param expectedRevision revision guard for the owner patch (-1 when unused)
 * @param sourcePath       the {@code .bbmodel} among the assets, when shipped
 * @param assets           binaries to stage (shipped assets + thumbnails)
 * @param actor            identity for journals and the audit log
 */
public record ModelTransaction(
        String pack,
        String ownerPath,
        boolean createOwner,
        String ownerId,
        String ownerType,
        String ownerName,
        String modelReference,
        List<String> ownerField,
        int expectedRevision,
        String sourcePath,
        List<Asset> assets,
        String actor) {

    public ModelTransaction {
        ownerPath = blankToEmpty(ownerPath);
        ownerId = blankToEmpty(ownerId);
        ownerType = blankToEmpty(ownerType);
        ownerName = blankToEmpty(ownerName);
        modelReference = blankToEmpty(modelReference);
        sourcePath = blankToEmpty(sourcePath);
        ownerField = ownerField == null ? List.of() : List.copyOf(ownerField);
        assets = assets == null ? List.of() : List.copyOf(assets);
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(actor, "actor");
    }

    /** One binary staged by the transaction: a shipped asset or a thumbnail. */
    public record Asset(String path, byte[] bytes) {}

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
