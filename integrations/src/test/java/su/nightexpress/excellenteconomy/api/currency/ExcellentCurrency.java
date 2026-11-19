package su.nightexpress.excellenteconomy.api.currency;

/**
 * A test double with the real type's name: CoinsEngine's API cannot be compiled against (it targets a newer
 * Java than Materione), and the adapter reaches it by reflection, so the test supplies a class of the same
 * name and method shapes for the reflection to find.
 */
public interface ExcellentCurrency {
    String getId();
}
