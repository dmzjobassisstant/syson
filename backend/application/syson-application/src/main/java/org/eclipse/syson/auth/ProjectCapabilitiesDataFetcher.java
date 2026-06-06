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
 * Compatibility data fetcher for Sirius Web frontend project capability fields.
 *
 * <p>The bundled frontend asks for {@code project.capabilities}. This custom
 * 2025.6.1 backend did not expose those fields, causing project list GraphQL
 * validation errors after login. Returning permissive capabilities matches the
 * current superuser/default deployment behavior and keeps the app mounted.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "Project", field = "capabilities")
public class ProjectCapabilitiesDataFetcher implements IDataFetcherWithFieldCoordinates<Map<String, Object>> {

    @Override
    public Map<String, Object> get(DataFetchingEnvironment environment) throws Exception {
        return Map.of(
                "canDownload", true,
                "canRename", true,
                "canDelete", true,
                "canEdit", true,
                "canDuplicate", true,
                "settings", Map.of("canView", true));
    }
}
