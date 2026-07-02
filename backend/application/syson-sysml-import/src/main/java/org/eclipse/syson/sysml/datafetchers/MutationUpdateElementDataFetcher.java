/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     syson-team
 *******************************************************************************/
package org.eclipse.syson.sysml.datafetchers;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.sirius.components.annotations.spring.graphql.QueryDataFetcher;
import org.eclipse.sirius.components.core.api.IPayload;
import org.eclipse.sirius.components.graphql.api.IDataFetcherWithFieldCoordinates;
import org.eclipse.sirius.components.graphql.api.IEditingContextDispatcher;
import org.eclipse.sirius.components.graphql.api.IExceptionWrapper;
import org.eclipse.syson.sysml.dto.UpdateElementInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.schema.DataFetchingEnvironment;

/**
 * Data fetcher for the field Mutation#updateElement.
 * <p>
 * Provides direct element modification (rename, set properties, set body)
 * without requiring a collaborative representation subscription.
 * </p>
 *
 * @author syson-team
 */
@QueryDataFetcher(type = "Mutation", field = "updateElement")
public class MutationUpdateElementDataFetcher implements IDataFetcherWithFieldCoordinates<CompletableFuture<IPayload>> {

    private static final String INPUT_ARGUMENT = "input";

    private final Logger logger = LoggerFactory.getLogger(MutationUpdateElementDataFetcher.class);

    private final ObjectMapper objectMapper;

    private final IExceptionWrapper exceptionWrapper;

    private final IEditingContextDispatcher editingContextDispatcher;

    public MutationUpdateElementDataFetcher(ObjectMapper objectMapper, IExceptionWrapper exceptionWrapper,
            IEditingContextDispatcher editingContextDispatcher) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.exceptionWrapper = Objects.requireNonNull(exceptionWrapper);
        this.editingContextDispatcher = Objects.requireNonNull(editingContextDispatcher);
    }

    @Override
    public CompletableFuture<IPayload> get(DataFetchingEnvironment environment) throws Exception {
        Object argument = environment.getArgument(INPUT_ARGUMENT);
        var input = this.objectMapper.convertValue(argument, UpdateElementInput.class);

        this.logger.info("updateElement mutation: editingContextId={}, elementId={}, newLabel={}, newBody={}, properties={}",
                input.editingContextId(), input.elementId(), input.newLabel(), input.newBody(), input.properties());

        return this.exceptionWrapper.wrapMono(
                () -> this.editingContextDispatcher.dispatchMutation(input.editingContextId(), input)
                        .doOnError(e -> this.logger.error("updateElement dispatch FAILED for element {}", input.elementId(), e))
                        .doOnNext(p -> this.logDispatchResult(p)),
                input
        ).toFuture();
    }

    private void logDispatchResult(IPayload payload) {
        String typeName;
        if (payload == null) {
            typeName = "null";
        } else {
            typeName = payload.getClass().getSimpleName();
        }
        this.logger.info("updateElement dispatch result: {}", typeName);
    }
}
