/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import org.eclipse.sirius.components.annotations.spring.graphql.QueryDataFetcher;
import org.eclipse.sirius.components.graphql.api.IDataFetcherWithFieldCoordinates;

import graphql.schema.DataFetchingEnvironment;

/**
 * Compatibility data fetcher for Sirius Web frontend locale bootstrap.
 *
 * <p>The packaged frontend calls {@code viewer.language} during startup. If the
 * backend schema does not expose the field, GraphQL validation fails before
 * React mounts and authenticated users see a blank page.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "Viewer", field = "language")
public class ViewerLanguageDataFetcher implements IDataFetcherWithFieldCoordinates<String> {

    @Override
    public String get(DataFetchingEnvironment environment) throws Exception {
        return "en";
    }
}
