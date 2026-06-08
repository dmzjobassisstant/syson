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
import org.eclipse.sirius.web.application.project.dto.ProjectTemplateDTO;
import org.eclipse.sirius.web.application.project.services.api.IProjectTemplateApplicationService;
import org.springframework.data.domain.PageRequest;

import graphql.schema.DataFetchingEnvironment;

/**
 * Compatibility data fetcher for newer Sirius Web frontend template queries.
 *
 * <p>The packaged frontend used by this deployment can query
 * {@code viewer.allProjectTemplates} when the user opens the project template
 * modal. The 2025.6.1 backend exposes only the paginated
 * {@code viewer.projectTemplates}. Returning the same template list through the
 * newer field keeps the create/open path compatible without rebuilding the
 * GitHub-Packages-auth frontend.</p>
 *
 * @author Damuza Consulting
 */
@QueryDataFetcher(type = "Viewer", field = "allProjectTemplates")
public class ViewerAllProjectTemplatesDataFetcher implements IDataFetcherWithFieldCoordinates<List<ProjectTemplateDTO>> {

    private static final int MAX_TEMPLATES = 100;

    private final IProjectTemplateApplicationService projectTemplateApplicationService;

    public ViewerAllProjectTemplatesDataFetcher(IProjectTemplateApplicationService projectTemplateApplicationService) {
        this.projectTemplateApplicationService = projectTemplateApplicationService;
    }

    @Override
    public List<ProjectTemplateDTO> get(DataFetchingEnvironment environment) throws Exception {
        return this.projectTemplateApplicationService.findAll(PageRequest.of(0, MAX_TEMPLATES)).getContent();
    }
}
