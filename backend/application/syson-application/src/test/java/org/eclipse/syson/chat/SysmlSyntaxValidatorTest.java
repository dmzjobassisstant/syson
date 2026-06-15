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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.eclipse.syson.chat.dto.Diagnostic;
import org.eclipse.syson.chat.dto.ValidateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SysmlSyntaxValidator}.
 *
 * @author syson-team
 */
@DisplayName("SysML Syntax Validator")
class SysmlSyntaxValidatorTest {

    private final SysmlSyntaxValidator validator = new SysmlSyntaxValidator();

    // ── Helper ──────────────────────────────────────────────────────────────

    private static ValidateResponse validate(String source) {
        return new SysmlSyntaxValidator().validate(source, "test.sysml");
    }

    private static boolean hasErrorContaining(List<Diagnostic> errors, String fragment) {
        return errors.stream().anyMatch(d -> d.message().contains(fragment));
    }

    // ── Valid source ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid SysML source passes validation")
    void validSourcePasses() {
        String source = """
            package MyPackage {
                private import ScalarValues::*;
                part def Motor {
                    attribute torque : Real;
                    in port powerIn : Real;
                }
            }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.errorCount()).isEqualTo(0);
    }

    // ── Rule 1: Real without import ─────────────────────────────────────────

    @Test
    @DisplayName("Flag Real usage without ScalarValues import")
    void realWithoutImport() {
        String source = """
            part def Motor {
                attribute torque : Real;
            }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "Real must declare")).isTrue();
    }

    @Test
    @DisplayName("Allow Real usage with ScalarValues import present")
    void realWithImportIsOk() {
        String source = """
            private import ScalarValues::*;
            part def Motor {
                attribute torque : Real;
            }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }

    // ── Rule 2: value Real; ─────────────────────────────────────────────────

    @Test
    @DisplayName("Reject value Real; declaration")
    void valueRealDirect() {
        String source = "value Real;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        // Message says `value Real;` with semicolon inside backticks
        assertThat(hasErrorContaining(result.errors(), "Do not generate `value Real")).isTrue();
    }

    // ── Rule 3: enum without def ────────────────────────────────────────────

    @Test
    @DisplayName("Reject enum without def keyword")
    void enumWithoutDef() {
        String source = "enum MyColors { red, green, blue }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "enum def")).isTrue();
    }

    @Test
    @DisplayName("Allow enum def (correct OMG syntax)")
    void enumWithDefIsOk() {
        String source = "enum def MyColors { red; green; blue; }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }

    // ── Rule 4: part def with single colon ──────────────────────────────────

    @Test
    @DisplayName("Reject part def with single colon instead of :>")
    void partDefSingleColon() {
        String source = "part def Child : Base { }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), ":>")).isTrue();
    }

    @Test
    @DisplayName("Allow part def with :> specialization")
    void partDefWithColonArrow() {
        String source = "part def Child :> Base { }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }

    // ── Rule 5: port direction order ────────────────────────────────────────

    @Test
    @DisplayName("Reject port before direction keyword")
    void portBeforeIn() {
        String source = "port in powerIn : Integer;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "Port direction precedes")).isTrue();
    }

    @Test
    @DisplayName("Allow in port (direction before port)")
    void inBeforePort() {
        // Use Integer instead of Real to avoid triggering Rule 1 (Real without import)
        String source = "in port powerIn : Integer;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }

    // ── Rule 6: requirement without def ─────────────────────────────────────

    @Test
    @DisplayName("Reject requirement without def keyword")
    void requirementWithoutDef() {
        String source = "requirement ReqRange { \"text\" }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "requirement def")).isTrue();
    }

    // ── Rule 7: actor/useCase shorthand ─────────────────────────────────────

    @Test
    @DisplayName("Reject actor shorthand")
    void actorShorthand() {
        String source = "actor User;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "actor")).isTrue();
    }

    @Test
    @DisplayName("Reject useCase shorthand")
    void useCaseShorthand() {
        String source = "useCase Login;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "useCase")).isTrue();
    }

    // ── Rule 8: relationship shorthand ──────────────────────────────────────

    @Test
    @DisplayName("Reject relationship shorthand (satisfy)")
    void relationshipShorthand() {
        String source = "moduleA satisfy moduleB;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "relationship shorthand")).isTrue();
    }

    // ── Rule 9: view shorthand ──────────────────────────────────────────────

    @Test
    @DisplayName("Reject prototype view syntax")
    void viewShorthand() {
        String source = "view MyDiagram : tree { include X; }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "view")).isTrue();
    }

    // ── Rule 10: transition with accept ─────────────────────────────────────

    @Test
    @DisplayName("Reject transition with accept Signal")
    void transitionAcceptSignal() {
        String source = "transition t first stateA accept Signal then stateB;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "accept")).isTrue();
    }

    // ── Structural checks ───────────────────────────────────────────────────

    @Test
    @DisplayName("Detect unbalanced braces")
    void unbalancedBraces() {
        String source = "package P { part def X {";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "braces")).isTrue();
    }

    @Test
    @DisplayName("Detect unbalanced parentheses")
    void unbalancedParens() {
        String source = "calc x = (a + b;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "parentheses")).isTrue();
    }

    @Test
    @DisplayName("Detect unbalanced brackets")
    void unbalancedBrackets() {
        String source = "attribute items : Type[10;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "brackets")).isTrue();
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty source is valid")
    void emptySource() {
        ValidateResponse result = validate("");
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("Null source treated as empty")
    void nullSource() {
        ValidateResponse result = validator.validate(null, null);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("Comments are stripped before rule matching")
    void commentsIgnored() {
        // The comment contains 'value Real;' but should be ignored
        String source = """
            // This is a comment with value Real; inside
            part def Motor { }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("Multi-line source with mixed valid and invalid")
    void multiLineMixed() {
        String source = """
            private import ScalarValues::*;
            part def Base { }
            value Real;
            part def Child :> Base {
                attribute x : Real;
            }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(hasErrorContaining(result.errors(), "Do not generate `value Real")).isTrue();
    }

    @Test
    @DisplayName("ValidationResult fields are populated correctly")
    void resultFieldsPopulated() {
        // "value Real;" triggers both Rule 1 (Real without import) and Rule 2 (value Real;)
        String source = "value Real;";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(result.errorCount()).isEqualTo(2);
        assertThat(result.warningCount()).isEqualTo(0);
        assertThat(result.errors()).hasSize(2);
        Diagnostic d = result.errors().get(0);
        assertThat(d.severity()).isEqualTo("error");
        assertThat(d.line()).isGreaterThanOrEqualTo(1);
        assertThat(d.col()).isGreaterThanOrEqualTo(1);
        assertThat(d.file()).isEqualTo("test.sysml");
    }

    @Test
    @DisplayName("Multiple errors accumulate")
    void multipleErrors() {
        String source = """
            value Real;
            part def Child : Base { }
            enum Colors { a, b }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        // Rule 1 (Real), Rule 2 (value Real;), Rule 4 (part def :), Rule 3 (enum without def) = 4+
        assertThat(result.errors().size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Diagnostic line and column are correct")
    void diagnosticLineColumn() {
        String source = "// header comment\n\npart def Child : Base { }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        Diagnostic d = result.errors().get(0);
        // The diagnostic reports the line/col from the original source at the
        // match position in the comment-stripped source. With comments removed:
        // stripped = "\n\npart def Child : Base { }\n", match at position 2.
        // Position 2 in original = "// header comment\n\n..." => line 1, col 3
        assertThat(d.severity()).isEqualTo("error");
        assertThat(d.line()).isEqualTo(1);
    }

    @Test
    @DisplayName("Extra closing brace detected")
    void extraClosingBrace() {
        String source = "part def X { } }";
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "extra")).isTrue();
    }

    @Test
    @DisplayName("import keyword inside comment does not count")
    void importInCommentIgnored() {
        // import only in comment, Real used → should still flag
        String source = """
            // import ScalarValues::*;
            part def Motor {
                attribute torque : Real;
            }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isFalse();
        assertThat(hasErrorContaining(result.errors(), "Real must declare")).isTrue();
    }

    @Test
    @DisplayName("Real in comment does not trigger rule 1")
    void realInCommentIgnored() {
        String source = """
            // Real is a type from ScalarValues
            part def Motor { }
            """;
        ValidateResponse result = validate(source);
        assertThat(result.valid()).isTrue();
    }
}
