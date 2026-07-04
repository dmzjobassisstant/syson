/*******************************************************************************
 * Copyright (c) 2024, 2025 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.util;

/**
 * Color palette for SysON diagram edges.
 * Each relationship type gets a distinct, accessible color to improve diagram readability.
 *
 * MERGE-CONFLICT NOTE: This is a SysON-specific utility. No upstream conflicts expected.
 * If upstream adds new edge types, add their colors here.
 *
 * @author SysON UX Improvements
 */
public final class EdgeColorPalette {

    // Relationship type → color mapping (KerML/SysMLv2 stereotype aware)

    /** Subsetting (solid, closed arrow) — specialization + subsetting combined */
    public static final String SUBSETTING_COLOR = "#1565C0";    // Blue 800

    /** Dependency (dashed, open arrow) — general dependency */
    public static final String DEPENDENCY_COLOR = "#6D4C41";    // Brown 600

    /** Feature Typing (solid, dots arrow) — typed by relationship */
    public static final String FEATURE_TYPING_COLOR = "#6A1B9A"; // Purple 800

    /** Subclassification (solid, hollow triangle) — generalization */
    public static final String SUBCLASSIFICATION_COLOR = "#2E7D32"; // Green 800

    /** Redefinition (solid, closed arrow) — redefines */
    public static final String REDEFINITION_COLOR = "#C62828";   // Red 800

    /** Allocation (solid, open arrow) — allocate */
    public static final String ALLOCATE_COLOR = "#E65100";       // Orange 900

    /** Transition (dashed/solid, open arrow) — state/action transition */
    public static final String TRANSITION_COLOR = "#00838F";     // Cyan 800

    /** Succession (dashed, open arrow) — happens before */
    public static final String SUCCESSION_COLOR = "#4527A0";     // Deep Purple 800

    /** Flow Connection (solid, filled arrow) — item flow */
    public static final String FLOW_CONNECTION_COLOR = "#37474F"; // Blue Grey 800

    /** Binding Connector (solid, diamond arrow) — binding */
    public static final String BINDING_CONNECTOR_COLOR = "#BF360C"; // Deep Orange 900

    /** Interface Usage (solid, open arrow) — interface realization */
    public static final String INTERFACE_USAGE_COLOR = "#004D40"; // Teal 900

    /** Feature Value (solid, none) — value assignment */
    public static final String FEATURE_VALUE_COLOR = "#827717";   // Lime 900

    /** Annotation (dashed, none) — comment/annotate */
    public static final String ANNOTATION_COLOR = "#757575";      // Grey 600

    /** Nested Usage / Definition Owned (solid, none) — containment */
    public static final String NESTED_COLOR = "#9E9E9E";         // Grey 500

    /** Include UseCase (dashed, open arrow) */
    public static final String INCLUDE_USE_CASE_COLOR = "#33691E"; // Light Green 900

    // Fallback / default
    public static final String DEFAULT_COLOR = "#212121";         // Grey 900

    /** Edge width — increased from 1px to 2px for better visibility */
    public static final int EDGE_WIDTH = 2;

    private EdgeColorPalette() {
        // Utility class
    }
}
