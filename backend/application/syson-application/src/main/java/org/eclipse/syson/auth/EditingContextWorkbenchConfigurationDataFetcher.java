/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import java.util.List;
import java.util.Map;

import org.eclipse.sirius.components.annotations.spring.graphql.QueryDataFetcher;
import org.eclipse.sirius.components.graphql.api.IDataFetcherWithFieldCoordinates;

import graphql.schema.DataFetchingEnvironment;

/**
 * Compatibility data fetcher for the newer Sirius Web workbench bootstrap.
 *
 * <p>The bundled frontend queries {@code EditingContext.workbenchConfiguration}
 * when a project is opened.  The 2025.6.1 backend does not provide that field,
 * so GraphQL validation fails and the project page goes blank.  Returning an
 * empty/default workbench configuration lets the frontend mount and preserve its
 * client-side default panels.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "EditingContext", field = "workbenchConfiguration")
public class EditingContextWorkbenchConfigurationDataFetcher implements IDataFetcherWithFieldCoordinates<Map<String, Object>> {

    @Override
    public Map<String, Object> get(DataFetchingEnvironment environment) throws Exception {
        return Map.of(
                "mainPanel", Map.of(
                        "id", "main",
                        "representationEditors", List.of()),
                "workbenchPanels", List.of(
                        Map.of(
                                "id", "left",
                                "isOpen", Boolean.TRUE,
                                "views", List.of(
                                        this.defaultView("explorer", true),
                                        this.defaultView("views-explorer", false),
                                        this.defaultView("validation", false),
                                        this.defaultView("search", false))),
                        Map.of(
                                "id", "right",
                                "isOpen", Boolean.TRUE,
                                "views", List.of(
                                        this.defaultView("details", true),
                                        this.defaultView("related-elements", false)))));
    }

    private Map<String, Object> defaultView(String id, boolean active) {
        return Map.of(
                "__typename", "DefaultViewConfiguration",
                "id", id,
                "isActive", active);
    }
}
