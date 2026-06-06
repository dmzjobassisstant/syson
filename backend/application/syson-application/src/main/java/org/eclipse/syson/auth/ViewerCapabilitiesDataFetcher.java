/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import java.util.Map;

import org.eclipse.sirius.components.annotations.spring.graphql.QueryDataFetcher;
import org.eclipse.sirius.components.graphql.api.IDataFetcherWithFieldCoordinates;

import graphql.schema.DataFetchingEnvironment;

/**
 * Compatibility data fetcher for Sirius Web frontend capability bootstrap.
 *
 * <p>The packaged frontend calls {@code viewer.capabilities} before it renders
 * the project list. In this customized build the underlying 2025.6.1 backend
 * schema does not expose that newer Sirius Web field, so the query fails
 * validation and leaves the root app shell blank. Returning permissive
 * capabilities restores the standard project/libraries landing page.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "Viewer", field = "capabilities")
public class ViewerCapabilitiesDataFetcher implements IDataFetcherWithFieldCoordinates<Map<String, Object>> {

    @Override
    public Map<String, Object> get(DataFetchingEnvironment environment) throws Exception {
        return Map.of(
                "projects", Map.of(
                        "canList", true,
                        "canCreate", true,
                        "canUpload", true),
                "libraries", Map.of(
                        "canList", true));
    }
}
