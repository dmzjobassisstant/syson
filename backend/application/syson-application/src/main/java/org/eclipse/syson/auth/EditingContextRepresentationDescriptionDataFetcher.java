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
 * Compatibility data fetcher for the newer Sirius Web explorer filter query.
 *
 * <p>The bundled frontend asks {@code EditingContext.representationDescription}
 * to load tree filters. The 2025.6.1 backend exposes representation description
 * metadata through {@code representationDescriptions(...)} but does not expose
 * this singular field. Returning an empty tree-description filter set prevents
 * the workbench from showing a validation snackbar while preserving the normal
 * explorer tree behavior.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "EditingContext", field = "representationDescription")
public class EditingContextRepresentationDescriptionDataFetcher implements IDataFetcherWithFieldCoordinates<Object> {

    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {
        return null;
    }
}
