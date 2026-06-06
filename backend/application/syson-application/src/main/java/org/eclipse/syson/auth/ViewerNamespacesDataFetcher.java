/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import java.util.List;

import org.eclipse.sirius.components.annotations.spring.graphql.QueryDataFetcher;
import org.eclipse.sirius.components.graphql.api.IDataFetcherWithFieldCoordinates;

import graphql.schema.DataFetchingEnvironment;

/**
 * Compatibility data fetcher for Sirius Web frontend namespace bootstrap.
 *
 * <p>The packaged frontend calls {@code viewer.namespaces} during startup. If
 * this field is missing from the schema, the initial GraphQL query fails
 * validation and the app remains blank after login.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "Viewer", field = "namespaces")
public class ViewerNamespacesDataFetcher implements IDataFetcherWithFieldCoordinates<List<String>> {

    @Override
    public List<String> get(DataFetchingEnvironment environment) throws Exception {
        return List.of();
    }
}
