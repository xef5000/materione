package dev.materione.behavior.expr;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the universal expression language
 * (runtime-systems §2.1). Grammar, loosest to tightest binding:
 *
 * <pre>
 * expr        := ternary
 * ternary     := or ('?' expr ':' expr)?
 * or          := and ('||' and)*
 * and         := equality ('&&' equality)*
 * equality    := comparison (('==' | '!=') comparison)*
 * comparison  := additive (('&lt;' | '&lt;=' | '&gt;' | '&gt;=') additive)*
 * additive    := multiplicative (('+' | '-') multiplicative)*
 * multiplicative := unary (('*' | '/' | '%') unary)*
 * unary       := '!' unary | '-' unary | primary
 * primary     := NUMBER | STRING | true | false | null | path | call | '(' expr ')'
 * path        := IDENT ('.' IDENT)*
 * call        := path '(' (expr (',' expr)*)? ')'
 * </pre>
 *
 * <p>A call names a function in the {@link FunctionResolver}: a bare name is a Materione function
 * ({@code contains(…)}), a two-part dotted name is an addon's ({@code example.greet(…)}, looked up
 * as {@code example:greet}). The name is resolved and the argument count checked here, at load.
 *
 * <p>Parse errors throw {@link ExpressionException} at load — a broken rule
 * never reaches the runtime.
 */
public final class ExpressionParser {

    private final String source;
    private final FunctionResolver functions;
    private final List<Token> tokens;
    private int position;

    private ExpressionParser(String source, FunctionResolver functions) {
        this.source = source;
        this.functions = functions;
        this.tokens = lex(source);
    }

    /** Parses one expression with no functions; a call in it is an unknown-function error. */
    public static Expression parse(String source) {
        return parse(source, FunctionResolver.NONE);
    }

    /**
     * Parses one expression, binding its calls against {@code functions}; throws
     * {@link ExpressionException} on bad syntax, an unknown function or a wrong argument count.
     */
    public static Expression parse(String source, FunctionResolver functions) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("empty expression");
        }
        var parser = new ExpressionParser(source, functions == null ? FunctionResolver.NONE : functions);
        ExprNode tree = parser.ternary();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing input '" + parser.peek().text() + "'");
        }
        return new Expression(source, tree);
    }

    // --- grammar -------------------------------------------------------------

    private ExprNode ternary() {
        ExprNode condition = or();
        if (match("?")) {
            ExprNode ifTrue = ternary();
            expect(":");
            ExprNode ifFalse = ternary();
            return new ExprNode.Ternary(condition, ifTrue, ifFalse);
        }
        return condition;
    }

    private ExprNode or() {
        ExprNode left = and();
        while (match("||")) {
            left = new ExprNode.Binary("||", left, and());
        }
        return left;
    }

    private ExprNode and() {
        ExprNode left = equality();
        while (match("&&")) {
            left = new ExprNode.Binary("&&", left, equality());
        }
        return left;
    }

    private ExprNode equality() {
        ExprNode left = comparison();
        while (match("==") || match("!=")) {
            left = new ExprNode.Binary(previous().text(), left, comparison());
        }
        return left;
    }

    private ExprNode comparison() {
        ExprNode left = additive();
        while (match("<=") || match(">=") || match("<") || match(">")) {
            left = new ExprNode.Binary(previous().text(), left, additive());
        }
        return left;
    }

    private ExprNode additive() {
        ExprNode left = multiplicative();
        while (match("+") || match("-")) {
            left = new ExprNode.Binary(previous().text(), left, multiplicative());
        }
        return left;
    }

    private ExprNode multiplicative() {
        ExprNode left = unary();
        while (match("*") || match("/") || match("%")) {
            left = new ExprNode.Binary(previous().text(), left, unary());
        }
        return left;
    }

    private ExprNode unary() {
        if (match("!")) {
            return new ExprNode.Unary("!", unary());
        }
        if (match("-")) {
            return new ExprNode.Unary("-", unary());
        }
        return primary();
    }

    private ExprNode primary() {
        Token token = peek();
        switch (token.type()) {
            case NUMBER -> {
                advance();
                return new ExprNode.Num(Double.parseDouble(token.text()));
            }
            case STRING -> {
                advance();
                return new ExprNode.Str(token.value());
            }
            case IDENT -> {
                advance();
                var segments = new ArrayList<String>();
                segments.add(token.text());
                while (match(".")) {
                    Token next = peek();
                    if (next.type() != TokenType.IDENT) {
                        throw error("expected an identifier after '.'");
                    }
                    segments.add(next.text());
                    advance();
                }
                if (match("(")) {
                    return call(segments, token.start());
                }
                if (segments.size() == 1) {
                    switch (token.text()) {
                        case "true" -> {
                            return new ExprNode.Bool(true);
                        }
                        case "false" -> {
                            return new ExprNode.Bool(false);
                        }
                        case "null" -> {
                            return new ExprNode.Null();
                        }
                        default -> {
                            // a variable
                        }
                    }
                }
                return new ExprNode.Path(List.copyOf(segments));
            }
            case OPERATOR -> {
                if (match("(")) {
                    ExprNode grouped = ternary();
                    expect(")");
                    return grouped;
                }
                throw error("unexpected '" + token.text() + "'");
            }
        }
        throw error("unexpected end of expression");
    }

    /** A call; the opening parenthesis is already consumed. Resolves the name and checks the arity. */
    private ExprNode call(List<String> segments, int start) {
        String spelled = String.join(".", segments);
        String authored = segments.size() == 1
                ? segments.get(0)
                : segments.size() == 2 ? segments.get(0) + ":" + segments.get(1) : spelled;
        ExpressionFunction function = functions.resolve(authored)
                .orElseThrow(() -> at(start, "unknown function '" + spelled + "'"));
        var arguments = new ArrayList<ExprNode>();
        if (!match(")")) {
            do {
                arguments.add(ternary());
            } while (match(","));
            expect(")");
        }
        checkArity(function, spelled, arguments.size(), start);
        return new ExprNode.Call(spelled, function, arguments);
    }

    private void checkArity(ExpressionFunction function, String spelled, int count, int start) {
        FunctionDescription description;
        try {
            description = function.describe();
        } catch (RuntimeException e) {
            throw at(start, "function '" + spelled + "' has no usable description");
        }
        int min = description.minArguments();
        int max = description.maxArguments();
        if (count < min || (max >= 0 && count > max)) {
            String expected = max < 0 ? "at least " + min
                    : min == max ? String.valueOf(min) : min + " to " + max;
            throw at(start, "function '" + spelled + "' takes " + expected + " argument(s), got " + count);
        }
    }

    // --- tokens ----------------------------------------------------------------

    private enum TokenType { NUMBER, STRING, IDENT, OPERATOR }

    /** One lexeme; {@code start} is its character offset in the source, for error positions. */
    private record Token(TokenType type, String text, String value, int start) {}

    private static List<Token> lex(String source) {
        var tokens = new ArrayList<Token>();
        int at = 0;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (Character.isWhitespace(c)) {
                at++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int start = at++;
                var text = new StringBuilder();
                while (at < source.length() && source.charAt(at) != c) {
                    if (source.charAt(at) == '\\' && at + 1 < source.length()) {
                        at++;
                    }
                    text.append(source.charAt(at));
                    at++;
                }
                if (at >= source.length()) {
                    throw new ExpressionException("unterminated string literal in: " + source);
                }
                at++; // closing quote
                tokens.add(new Token(TokenType.STRING, source.substring(start, at), text.toString(), start));
                continue;
            }
            if (Character.isDigit(c)
                    || (c == '.' && at + 1 < source.length() && Character.isDigit(source.charAt(at + 1)))) {
                int start = at;
                while (at < source.length()
                        && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
                    at++;
                }
                tokens.add(new Token(TokenType.NUMBER, source.substring(start, at), null, start));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = at;
                while (at < source.length() && Character.isJavaIdentifierPart(source.charAt(at))) {
                    at++;
                }
                tokens.add(new Token(TokenType.IDENT, source.substring(start, at), null, start));
                continue;
            }
            // two-char operators first
            String twoChar = source.length() - at >= 2 ? source.substring(at, at + 2) : "";
            if (twoChar.equals("==") || twoChar.equals("!=") || twoChar.equals("<=")
                    || twoChar.equals(">=") || twoChar.equals("&&") || twoChar.equals("||")) {
                tokens.add(new Token(TokenType.OPERATOR, twoChar, null, at));
                at += 2;
                continue;
            }
            if ("+-*/%<>!?:.(),".indexOf(c) >= 0) {
                tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c), null, at));
                at++;
                continue;
            }
            throw new ExpressionException(
                    "unexpected character '" + c + "' in expression: " + source);
        }
        return tokens;
    }

    // --- cursor helpers ----------------------------------------------------------

    private boolean atEnd() {
        return position >= tokens.size();
    }

    private void advance() {
        position++;
    }

    private Token peek() {
        return tokens.get(Math.min(position, tokens.size() - 1));
    }

    private Token previous() {
        return tokens.get(position - 1);
    }

    private boolean match(String operator) {
        if (!atEnd() && peek().text().equals(operator) && peek().type() == TokenType.OPERATOR) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String operator) {
        if (!match(operator)) {
            throw error("expected '" + operator + "'");
        }
    }

    private ExpressionException error(String message) {
        return new ExpressionException(message + " in expression: " + source);
    }

    /** An error at a character offset: 1-based, as an author counts characters. */
    private ExpressionException at(int offset, String message) {
        return new ExpressionException(message + " at position " + (offset + 1) + " in expression: " + source);
    }
}
