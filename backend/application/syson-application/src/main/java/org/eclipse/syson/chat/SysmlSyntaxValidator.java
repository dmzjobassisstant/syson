/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.syson.chat.dto.Diagnostic;
import org.eclipse.syson.chat.dto.ValidateResponse;
import org.springframework.stereotype.Service;

/**
 * Validates SysML v2 source text against OMG-compliant syntax rules.
 * <p>
 * Ported from the TypeScript validation engine at
 * {@code systems/sysmlv2-platform/packages/engine/src/validationRules.ts}.
 * Rules are intentionally aligned with the OMG Pilot Implementation and catch
 * shorthand accepted by earlier prototypes but rejected by
 * {@code SysMLInteractive.process(..., true)}.
 * </p>
 *
 * @author syson-team
 */
@Service
public class SysmlSyntaxValidator {

    // ── Known SysML v2 keywords ────────────────────────────────────────────

    private static final Set<String> KNOWN_KEYWORDS = Set.of(
        "abstract", "accept", "action", "actor", "alias", "all", "allocate",
        "analysis", "and", "as", "assert", "assign", "assume", "attribute",
        "becoming", "binding", "block", "by", "calc", "case", "classifier",
        "comment", "compose", "conjugate", "connect", "connection", "constraint",
        "decision", "def", "default", "define", "dependency", "derivation",
        "derived", "determination", "difference", "disjoint", "do", "doc",
        "documentation", "else", "end", "enum", "equals", "exhibit", "existential",
        "exit", "expression", "feature", "filter", "first", "flow", "for",
        "forall", "fork", "function", "hastype", "if", "import", "in", "include",
        "individual", "inout", "intersection", "invariant", "is", "item",
        "join", "language", "loop", "member", "merge", "metadata", "model",
        "namespace", "nonunique", "not", "occurrence", "of", "or", "ordered",
        "out", "package", "part", "perform", "port", "portion", "private",
        "public", "readonly", "real", "redefines", "reduction", "ref",
        "reference", "refine", "require", "requirement", "return", "satisfy",
        "send", "sequence", "snapshot", "source", "specialization", "state",
        "step", "struct", "subclassification", "subject", "subsets", "succession",
        "symmetric", "target", "then", "time", "to", "trace", "transition",
        "true", "type", "union", "use", "useCase", "value", "variation",
        "verify", "view", "viewpoint", "while"
    );

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Validates the given SysML source text and returns a result with diagnostics.
     *
     * @param source the SysML v2 source text to validate
     * @param fileId a logical file identifier (e.g. "model.sysml")
     * @return a {@link ValidateResponse} with validity status and diagnostics
     */
    public ValidateResponse validate(String source, String fileId) {
        if (source == null) {
            source = "";
        }
        if (fileId == null) {
            fileId = "model.sysml";
        }

        List<Diagnostic> diagnostics = new ArrayList<>();

        // Strip line comments for rule matching (same as TypeScript version)
        String strippedSource = stripLineComments(source);

        // ── Rule 1: Real without import ─────────────────────────────────────
        if (Pattern.compile("\\bReal\\b").matcher(strippedSource).find()
            && !Pattern.compile("\\b(private\\s+)?import\\s+ScalarValues::\\*\\s*;")
                .matcher(strippedSource).find()) {
            int idx = indexOf(source, "\\bReal\\b");
            diagnostics.add(diag(idx, source,
                "Models using Real must declare `private import ScalarValues::*;`.",
                fileId));
        }

        // ── Rules 2-10: regex-driven rules ──────────────────────────────────
        List<Rule> rules = List.of(
            new Rule(Pattern.compile("\\bvalue\\s+Real\\s*;"),
                "Do not generate `value Real;`; use `private import ScalarValues::*;` and type attributes as `Real`."),
            new Rule(Pattern.compile("\\benum\\s+(?!def\\b)(\\w+)\\s*\\{"),
                "Use OMG enum definitions: `enum def Name { enum literal; }`, not `enum Name { a, b }`."),
            new Rule(Pattern.compile("\\bpart\\s+def\\s+(\\w+)\\s*:\\s*(?!>)(\\w+)"),
                "Use OMG specialization syntax: `part def Child :> Base`, not `part def Child : Base`."),
            new Rule(Pattern.compile("\\bport\\s+(in|out|inout)\\s+(\\w+)"),
                "Port direction precedes `port`: use `in port power : T;`, `out port p : T;`."),
            new Rule(Pattern.compile("\\brequirement\\s+(?!def\\b)(\\w+)\\s*\\{\\s*\""),
                "Use OMG requirement definitions: `requirement def ReqRange { doc /* text */ }`."),
            new Rule(Pattern.compile("^\\s*(actor\\s+\\w+|useCase\\s+\\w+)\\s*;", Pattern.MULTILINE),
                "Do not generate prototype `actor`/`useCase` shorthand in OMG-valid models; use comments or supported library syntax only after validating with the OMG validator."),
            new Rule(Pattern.compile("^\\s*\\w+(?:\\.\\w+)?\\s+(satisfy|trace|derive|refine|verify|include|extend|allocate)\\s+\\w+(?:\\.\\w+)?\\s*;", Pattern.MULTILINE),
                "Do not generate prototype relationship shorthand; omit it, comment it, or replace with OMG-validated relationship syntax."),
            new Rule(Pattern.compile("\\bview\\s+\\w+\\s*:\\s*\\w+\\s*\\{"),
                "Prototype `view Name : kind { include ... }` is not OMG syntax; store diagram grouping in custom diagram comments/metadata instead."),
            new Rule(Pattern.compile("\\btransition\\s+\\w+\\s+first\\s+\\w+\\s+accept\\s+\\w+\\s+then\\s+\\w+\\s*;"),
                "Transitions with `accept Signal` require declared/typed trigger definitions; generated examples should use `transition t first A then B;` unless triggers are modeled.")
        );

        for (Rule rule : rules) {
            Matcher matcher = rule.pattern.matcher(strippedSource);
            while (matcher.find()) {
                diagnostics.add(diag(matcher.start(), source, rule.message, fileId));
            }
        }

        // ── Structural checks ───────────────────────────────────────────────
        checkBalancedDelimiters(source, fileId, diagnostics);

        long errorCount = diagnostics.stream().filter(d -> "error".equals(d.severity())).count();
        long warningCount = diagnostics.stream().filter(d -> "warning".equals(d.severity())).count();

        return new ValidateResponse(
            diagnostics.isEmpty(),
            diagnostics,
            (int) errorCount,
            (int) warningCount
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String stripLineComments(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\\n", -1)) {
            int commentIdx = line.indexOf("//");
            if (commentIdx >= 0) {
                sb.append(line, 0, commentIdx);
            } else {
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int indexOf(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        return m.find() ? m.start() : -1;
    }

    private static Diagnostic diag(int charIndex, String source, String message, String fileId) {
        int line = 1;
        int col = 1;
        if (charIndex >= 0 && charIndex < source.length()) {
            for (int i = 0; i < charIndex; i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
        }
        return new Diagnostic("error", line, col, message, fileId);
    }

    private void checkBalancedDelimiters(String source, String fileId, List<Diagnostic> diagnostics) {
        checkBalanced(source, '{', '}', "Unbalanced braces: missing closing '}'", fileId, diagnostics);
        checkBalanced(source, '(', ')', "Unbalanced parentheses: missing closing ')'", fileId, diagnostics);
        checkBalanced(source, '[', ']', "Unbalanced brackets: missing closing ']'", fileId, diagnostics);
    }

    private void checkBalanced(String source, char open, char close, String message, String fileId,
                               List<Diagnostic> diagnostics) {
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth < 0) {
                    diagnostics.add(diag(i, source,
                        "Unbalanced delimiter: extra '" + close + "' without matching '" + open + "'",
                        fileId));
                    return;
                }
            }
        }
        if (depth > 0) {
            diagnostics.add(diag(0, source, message, fileId));
        }
    }

    // ── Inner types ─────────────────────────────────────────────────────────

    private record Rule(Pattern pattern, String message) {}
}
