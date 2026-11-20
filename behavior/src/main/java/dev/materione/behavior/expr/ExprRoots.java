package dev.materione.behavior.expr;

import java.util.Set;

/** Which variable roots an expression reads ({@code player} in {@code player.level > 3}). */
public final class ExprRoots {

    private ExprRoots() {}

    /**
     * Adds the first segment of every variable path in {@code node} to {@code roots}. A read of an item-scoped
     * variable ({@code vars.item.pack.key}, logic.md §38) is a read of the run's item stack, so it also adds
     * {@code item}: a trigger or GUI that provides no {@code item} root cannot name a stack, and the usual
     * unknown-root check (MTR-0060) says so.
     */
    public static void collect(ExprNode node, Set<String> roots) {
        switch (node) {
            case ExprNode.Path path -> {
                if (!path.segments().isEmpty()) {
                    roots.add(path.segments().get(0));
                    if (path.segments().size() > 2 && "vars".equals(path.segments().get(0))
                            && "item".equals(path.segments().get(1))) {
                        roots.add("item");
                    }
                }
            }
            case ExprNode.Unary unary -> collect(unary.operand(), roots);
            case ExprNode.Binary binary -> {
                collect(binary.left(), roots);
                collect(binary.right(), roots);
            }
            case ExprNode.Ternary ternary -> {
                collect(ternary.condition(), roots);
                collect(ternary.ifTrue(), roots);
                collect(ternary.ifFalse(), roots);
            }
            case ExprNode.Call call -> call.arguments().forEach(argument -> collect(argument, roots));
            case ExprNode.Num ignored -> { }
            case ExprNode.Str ignored -> { }
            case ExprNode.Bool ignored -> { }
            case ExprNode.Null ignored -> { }
        }
    }
}
