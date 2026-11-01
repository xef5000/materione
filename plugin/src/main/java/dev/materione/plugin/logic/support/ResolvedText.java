package dev.materione.plugin.logic.support;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Freezes an already-rendered parameter without evaluating it or its placeholders a second time. */
public record ResolvedText(String source) implements TemplateText {
    @Override public String render(LogicContext context) {
        return source;
    }

    public static CompletionStage<TemplateText> resolve(TemplateText text, LogicContext context, ReadExecutor reads) {
        return text.renderResumable(context, reads).thenApply(ResolvedText::new);
    }

    public static CompletionStage<Optional<TemplateText>> resolve(
            Optional<TemplateText> text, LogicContext context, ReadExecutor reads) {
        return text.isEmpty() ? CompletableFuture.completedFuture(Optional.empty())
                : resolve(text.get(), context, reads).thenApply(Optional::of);
    }
}
