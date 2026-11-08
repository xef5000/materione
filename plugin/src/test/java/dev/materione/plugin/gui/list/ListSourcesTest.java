package dev.materione.plugin.gui.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The built-in list sources (logic.md §4.5): their parameters, their entries and the fields they declare. */
class ListSourcesTest {

    private static final NamespacedId FAVOURITES = NamespacedId.of("shop", "favourites");

    private static MapLogicContext context(VariableBinding variables) {
        var root = new LinkedHashMap<String, Object>();
        root.put("vars", new LinkedHashMap<String, Object>());
        var context = new MapLogicContext(root);
        context.withVariables(variables);
        return context;
    }

    private static VariableBinding favourites(Object stored, VariableType type, VariableScope scope) {
        Object defaultValue = switch (type) {
            case NUMBER -> 0.0d;
            case BOOLEAN -> Boolean.FALSE;
            case STRING -> "";
        };
        var declaration = new VariableDeclaration(FAVOURITES, scope, type, defaultValue, "");
        return new VariableBinding() {
            @Override
            public Optional<VariableDeclaration> declaration(NamespacedId id) {
                return FAVOURITES.equals(id) ? Optional.of(declaration) : Optional.empty();
            }

            @Override
            public Optional<Object> stored(VariableDeclaration found) {
                return Optional.ofNullable(stored);
            }

            @Override
            public boolean store(VariableDeclaration found, Object value) {
                return false;
            }
        };
    }

    private static <P> List<Map<String, Object>> read(ListSource<P> source, Map<String, ?> params, VariableBinding bound) {
        P parsed = source.parse(ParamReader.of(params));
        return source.entries(parsed, context(bound));
    }

    @Test
    void theBuiltinsRegisterTheirIdsAndDescribeTheFieldsTheyProduce() {
        var sources = BuiltinListSources.all(ItemCatalog.none());

        assertThat(sources).extracting(ListSource::id)
                .containsExactly("online_players", "pack_items", "content", "variable", "static");
        assertThat(sources.get(1).describe().cached()).isTrue();
        assertThat(sources.get(1).describe().shorthand()).isEqualTo("pack");
        assertThat(sources.get(0).describe().entry()).extracting(variable -> variable.path())
                .containsExactly("entry.name", "entry.uuid");
        assertThat(sources.get(2).describe().shorthand()).isNull();
        assertThat(sources.get(3).describe().shorthand()).isEqualTo("var");
    }

    @Test
    void anOnlinePlayersSourceTakesOnlyAFlagForTheViewer() {
        var source = new OnlinePlayersSource();

        assertThat(source.parse(ParamReader.of(Map.of())).excludeViewer()).isFalse();
        assertThat(source.parse(ParamReader.of(Map.of("exclude_viewer", true))).excludeViewer()).isTrue();
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("exclude_viewer", "yes"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exclude_viewer must be true or false");
        assertThat(source.describe().entry()).extracting(variable -> variable.path())
                .containsExactly("entry.name", "entry.uuid");
    }

    @Test
    void aStaticSourceReadsItsWrittenEntriesAndRejectsAnythingElse() {
        var source = new StaticListSource();
        var written = List.of(Map.of("name", "Ada", "level", 3), Map.of("name", "Grace", "level", 9));

        assertThat(read(source, Map.of("entries", written), VariableBinding.NONE))
                .containsExactly(Map.of("name", "Ada", "level", 3), Map.of("name", "Grace", "level", 9));
        assertThat(read(source, Map.of("entries", List.of()), VariableBinding.NONE)).isEmpty();
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entries is a list of mappings");
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("entries", List.of("Ada")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holds mappings of fields");
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("entries", List.of(Map.of("tags", List.of("x")))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text, numbers or booleans");
    }

    @Test
    void aVariableSourceReadsADeclaredStringAsSeparatedPieces() {
        var source = new VariableListSource();
        var params = source.parse(ParamReader.of(Map.of("var", "shop:favourites")));
        var bound = favourites("ada, grace ,,linus", VariableType.STRING, VariableScope.PLAYER);

        assertThat(source.entries(params, context(bound)))
                .containsExactly(Map.of("value", "ada"), Map.of("value", "grace"), Map.of("value", "linus"));
    }

    @Test
    void aVariableSourceFailsOnAVariableThatIsNotAPlayerOrServerString() {
        var source = new VariableListSource();
        var params = source.parse(ParamReader.of(Map.of("var", "shop:favourites")));

        assertThatThrownBy(() -> source.entries(params, context(favourites("1", VariableType.NUMBER,
                VariableScope.PLAYER)))).hasMessageContaining("is not a string variable");
        assertThatThrownBy(() -> source.entries(params, context(VariableBinding.NONE)))
                .hasMessageContaining("is not a declared variable");
    }

    @Test
    void aServerVariableIsReadLikeAPlayerVariable() {
        var source = new VariableListSource();
        var params = source.parse(ParamReader.of(Map.of("var", "shop:favourites")));

        assertThat(source.entries(params, context(favourites("x,y", VariableType.STRING, VariableScope.SERVER))))
                .containsExactly(Map.of("value", "x"), Map.of("value", "y"));
    }

    @Test
    void aVariableSourceTakesABareNameFromThePackOfTheListAndRefusesOneWithoutAPack() {
        var source = new VariableListSource();
        CompileEnvironment inShop = new CompileEnvironment() {
            @Override
            public String pack() {
                return "shop";
            }

            @Override
            public Optional<VariableDeclaration> variable(NamespacedId id) {
                return Optional.empty();
            }
        };

        assertThat(source.parse(ParamReader.of(Map.of("var", "favourites"), ExpressionCompiler.LITERAL, inShop))
                .variable()).isEqualTo(FAVOURITES);
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("var", "favourites"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs its pack");
    }

    @Test
    void aPackSourceNamesItsPackAndReadsNoItemsWithoutContent() {
        var source = new PackItemsSource(ItemCatalog.none());

        assertThat(source.parse(ParamReader.of(Map.of("pack", "shop"))).pack()).isEqualTo("shop");
        assertThat(source.entries(new PackItemsSource.Params("shop"), context(VariableBinding.NONE))).isEmpty();
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pack is required");
    }
}
