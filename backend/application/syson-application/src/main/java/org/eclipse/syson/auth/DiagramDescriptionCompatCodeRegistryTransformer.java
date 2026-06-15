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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.sirius.components.collaborative.diagrams.dto.GetNodeDescriptionsInput;
import org.eclipse.sirius.components.collaborative.diagrams.dto.GetNodeDescriptionsSuccessPayload;
import org.eclipse.sirius.components.core.api.IInput;
import org.eclipse.sirius.components.core.api.IPayload;
import org.eclipse.sirius.components.graphql.api.IEditingContextDispatcher;
import org.eclipse.sirius.components.graphql.api.IExceptionWrapper;
import org.eclipse.sirius.web.infrastructure.configuration.graphql.IGraphQLCodeRegistryTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;

/**
 * Wraps the upstream DiagramDescription.nodeDescriptions and
 * dropNodeCompatibility data fetchers with null-safe fallbacks.
 *
 * <p>The upstream fetcher dispatches a query via IEditingContextDispatcher to
 * retrieve node descriptions from the editing context event processor. When the
 * dispatch returns an empty Mono (e.g. the representation is corrupted or the
 * editing context can't find the diagram description), the CompletableFuture
 * resolves to {@code null}, violating the GraphQL non-null constraint on
 * {@code [NodeDescription!]!}. This null bubbles up and nullifies the entire
 * representation.description object, preventing the diagram from rendering.</p>
 *
 * <p>This transformer runs AFTER all IDataFetcherWithFieldCoordinates beans have
 * been registered. It replaces the fetcher with a delegating implementation
 * that dispatches the same query but falls back to an empty list when the
 * upstream would have returned null. For valid editing contexts, the palette
 * still works normally; for broken ones, at least the diagram renders.</p>
 *
 * @author Damuza Consulting
 */
@Component
public class DiagramDescriptionCompatCodeRegistryTransformer implements IGraphQLCodeRegistryTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagramDescriptionCompatCodeRegistryTransformer.class);

    private static final String DIAGRAM_DESCRIPTION = "DiagramDescription";
    private static final String NODE_DESCRIPTIONS = "nodeDescriptions";
    private static final String DROP_NODE_COMPATIBILITY = "dropNodeCompatibility";
    private static final String EDITING_CONTEXT_ID = "editingContextId";
    private static final String REPRESENTATION_ID = "representationId";

    private final IExceptionWrapper exceptionWrapper;
    private final IEditingContextDispatcher editingContextDispatcher;

    public DiagramDescriptionCompatCodeRegistryTransformer(IExceptionWrapper exceptionWrapper,
            IEditingContextDispatcher editingContextDispatcher) {
        this.exceptionWrapper = exceptionWrapper;
        this.editingContextDispatcher = editingContextDispatcher;
    }

    @Override
    public void transform(GraphQLCodeRegistry.Builder builder) {
        LOGGER.info("Wrapping DiagramDescription.{} and .{} with null-safe fallback fetchers", NODE_DESCRIPTIONS, DROP_NODE_COMPATIBILITY);

        NodeDescriptionsFetchDelegate nodeDescriptionsDelegate = new NodeDescriptionsFetchDelegate(
                this.exceptionWrapper, this.editingContextDispatcher);

        builder.dataFetcher(
                FieldCoordinates.coordinates(DIAGRAM_DESCRIPTION, NODE_DESCRIPTIONS),
                nodeDescriptionsDelegate);

        DataFetcher<List<Object>> emptyListFetcher = environment -> List.of();
        builder.dataFetcher(
                FieldCoordinates.coordinates(DIAGRAM_DESCRIPTION, DROP_NODE_COMPATIBILITY),
                emptyListFetcher);
    }

    /**
     * Delegates to IEditingContextDispatcher (same logic as the upstream
     * fetcher) but falls back to an empty list when the result would be null.
     */
    private static class NodeDescriptionsFetchDelegate implements DataFetcher<CompletableFuture<List<Object>>> {

        private final IExceptionWrapper exceptionWrapper;
        private final IEditingContextDispatcher editingContextDispatcher;

        NodeDescriptionsFetchDelegate(IExceptionWrapper exceptionWrapper, IEditingContextDispatcher editingContextDispatcher) {
            this.exceptionWrapper = exceptionWrapper;
            this.editingContextDispatcher = editingContextDispatcher;
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<List<Object>> get(DataFetchingEnvironment environment) throws Exception {
            Map<String, Object> localContext = environment.getLocalContext();

            Optional<String> editingContextId = Optional.ofNullable(localContext.get(EDITING_CONTEXT_ID))
                    .map(Object::toString);
            Optional<String> representationId = Optional.ofNullable(localContext.get(REPRESENTATION_ID))
                    .map(Object::toString);

            if (editingContextId.isPresent() && representationId.isPresent()) {
                GetNodeDescriptionsInput input = new GetNodeDescriptionsInput(
                        UUID.randomUUID(), editingContextId.get(), representationId.get());

                LOGGER.debug("Dispatching GetNodeDescriptions for editingContext={}, representation={}",
                        editingContextId.get(), representationId.get());

                return this.exceptionWrapper.wrapMono(() -> {
                    return this.editingContextDispatcher.dispatchQuery(editingContextId.get(), input);
                }, input)
                        .filter(GetNodeDescriptionsSuccessPayload.class::isInstance)
                        .cast(GetNodeDescriptionsSuccessPayload.class)
                        .map(GetNodeDescriptionsSuccessPayload::nodeDescriptions)
                        .map(list -> (List<Object>) (List<?>) list)
                        .defaultIfEmpty(List.of())
                        .toFuture()
                        .thenApply(result -> {
                            if (result == null) {
                                LOGGER.warn("GetNodeDescriptions returned null for ec={}, rep={} — falling back to empty list",
                                        editingContextId.get(), representationId.get());
                                return List.of();
                            }
                            LOGGER.debug("GetNodeDescriptions returned {} entries for ec={}, rep={}",
                                    result.size(), editingContextId.get(), representationId.get());
                            return result;
                        });
            }

            LOGGER.debug("Missing editingContextId or representationId in local context — returning empty list");
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
