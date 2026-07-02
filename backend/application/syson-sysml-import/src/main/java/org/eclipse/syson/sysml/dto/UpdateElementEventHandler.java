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
package org.eclipse.syson.sysml.dto;

import java.util.List;
import java.util.Objects;

import org.eclipse.sirius.components.collaborative.api.ChangeDescription;
import org.eclipse.sirius.components.collaborative.api.ChangeKind;
import org.eclipse.sirius.components.collaborative.api.IEditingContextEventHandler;
import org.eclipse.sirius.components.collaborative.api.Monitoring;
import org.eclipse.sirius.components.collaborative.messages.ICollaborativeMessageService;
import org.eclipse.sirius.components.core.api.ErrorPayload;
import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.sirius.components.core.api.IInput;
import org.eclipse.sirius.components.core.api.IObjectSearchService;
import org.eclipse.sirius.components.core.api.IPayload;
import org.eclipse.sirius.components.core.api.SuccessPayload;
import org.eclipse.sirius.components.emf.services.api.IEMFEditingContext;
import org.eclipse.sirius.components.representations.Message;
import org.eclipse.sirius.components.representations.MessageLevel;
import org.eclipse.syson.sysml.Comment;
import org.eclipse.syson.sysml.Element;
import org.eclipse.syson.sysml.OwningMembership;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Sinks.Many;
import reactor.core.publisher.Sinks.One;

/**
 * Event handler for the updateElement mutation.
 * <p>
 * This implements {@link IEditingContextEventHandler} (not IRepresentationEventHandler),
 * meaning it is always active and does NOT require a tree/form/diagram subscription
 * to be open. This allows direct element modification from REST clients, scripts,
 * and the test harness without needing the collaborative WebSocket subscription.
 * </p>
 *
 * @author syson-team
 */
@Service
public class UpdateElementEventHandler implements IEditingContextEventHandler {

    private final IObjectSearchService objectSearchService;

    private final ICollaborativeMessageService messageService;

    private final Counter counter;

    public UpdateElementEventHandler(IObjectSearchService objectSearchService,
            ICollaborativeMessageService messageService, MeterRegistry meterRegistry) {
        this.objectSearchService = Objects.requireNonNull(objectSearchService);
        this.messageService = Objects.requireNonNull(messageService);
        this.counter = Counter.builder(Monitoring.EVENT_HANDLER)
                .tag(Monitoring.NAME, this.getClass().getSimpleName())
                .register(meterRegistry);
    }

    @Override
    public boolean canHandle(IEditingContext editingContext, IInput input) {
        return input instanceof UpdateElementInput;
    }

    @Override
    public void handle(One<IPayload> payloadSink, Many<ChangeDescription> changeDescriptionSink,
            IEditingContext editingContext, IInput input) {
        this.counter.increment();

        List<Message> messages = List.of(new Message(
                this.messageService.invalidInput(input.getClass().getSimpleName(), UpdateElementInput.class.getSimpleName()),
                MessageLevel.ERROR));
        ChangeDescription changeDescription = new ChangeDescription(ChangeKind.NOTHING, editingContext.getId(), input);
        IPayload payload = null;

        if (input instanceof UpdateElementInput updateInput
                && editingContext instanceof IEMFEditingContext emfEditingContext) {

            Element element = this.findElement(updateInput.elementId(), emfEditingContext);
            if (element != null) {
                boolean changed = false;
                messages = List.of();

                // Rename: update declaredName
                if (updateInput.newLabel() != null && !updateInput.newLabel().isBlank()) {
                    element.setDeclaredName(updateInput.newLabel());
                    changed = true;
                }

                if (updateInput.newShortName() != null) {
                    if (updateInput.newShortName().isBlank()) {
                        element.setDeclaredShortName(null);
                    } else {
                        element.setDeclaredShortName(updateInput.newShortName());
                    }
                    changed = true;
                }

                // Update body/description via Comment
                if (updateInput.newBody() != null && !updateInput.newBody().isBlank()) {
                    this.updateBody(element, updateInput.newBody());
                    changed = true;
                }

                // Update arbitrary string properties (key-value pairs)
                if (updateInput.properties() != null && !updateInput.properties().isEmpty()) {
                    this.applyProperties(element, updateInput.properties());
                    changed = true;
                }

                if (changed) {
                    payload = new SuccessPayload(input.id(), messages);
                    changeDescription = new ChangeDescription(ChangeKind.SEMANTIC_CHANGE, editingContext.getId(), input);
                } else {
                    messages = List.of(new Message("No changes specified", MessageLevel.WARNING));
                }
            } else {
                messages = List.of(new Message("Element not found: " + updateInput.elementId(), MessageLevel.ERROR));
            }
        }

        if (payload == null) {
            payload = new ErrorPayload(input.id(), messages);
        }

        payloadSink.tryEmitValue(payload);
        changeDescriptionSink.tryEmitNext(changeDescription);
    }

    private Element findElement(String elementId, IEMFEditingContext emfEditingContext) {
        var optObject = this.objectSearchService.getObject(emfEditingContext, elementId);
        if (optObject.isPresent() && optObject.get() instanceof Element element) {
            return element;
        }
        return null;
    }

    /**
     * Updates or creates the body/description of an element.
     * For AttributeUsage elements, updates the LiteralString value.
     * For other elements, updates the Documentation Comment.
     */
    private void updateBody(Element element, String body) {
        // For AttributeUsage: update the LiteralString value directly
        if (element instanceof org.eclipse.syson.sysml.AttributeUsage) {
            var existingLit = element.getOwnedElement().stream()
                    .filter(org.eclipse.syson.sysml.LiteralString.class::isInstance)
                    .map(org.eclipse.syson.sysml.LiteralString.class::cast)
                    .findFirst();
            if (existingLit.isPresent()) {
                existingLit.get().setValue(body);
            } else {
                var lit = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createLiteralString();
                lit.setValue(body);
                var fv = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createFeatureValue();
                fv.getOwnedRelatedElement().add(lit);
                element.getOwnedRelationship().add(fv);
            }
            return;
        }

        // For other elements: update/create a Documentation Comment
        var existingDoc = element.getOwnedElement().stream()
                .filter(Comment.class::isInstance)
                .map(Comment.class::cast)
                .filter(c -> c.getOwnedRelationship().stream()
                        .anyMatch(OwningMembership.class::isInstance))
                .findFirst();

        if (existingDoc.isPresent()) {
            existingDoc.get().setBody(body);
        } else {
            var comment = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createComment();
            comment.setBody(body);
            var membership = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createOwningMembership();
            membership.getOwnedRelatedElement().add(comment);
            element.getOwnedRelationship().add(membership);
        }
    }

    /**
     * Applies arbitrary key-value properties to an element.
     * Currently supports setting declaredName and declaredShortName via the
     * properties map (alternative to newLabel/newShortName fields).
     * Also supports "value" to update the LiteralString value of AttributeUsages.
     */
    private void applyProperties(Element element, List<UpdateElementInput.KeyValueInput> properties) {
        for (var kv : properties) {
            if ("name".equals(kv.key())) {
                element.setDeclaredName(kv.value());
            }
            if ("shortName".equals(kv.key())) {
                if (kv.value().isBlank()) {
                    element.setDeclaredShortName(null);
                } else {
                    element.setDeclaredShortName(kv.value());
                }
            }
            if ("value".equals(kv.key())) {
                // Update the LiteralString child's value (e.g., AttributeUsage text)
                element.getOwnedElement().stream()
                    .filter(org.eclipse.syson.sysml.LiteralString.class::isInstance)
                    .map(org.eclipse.syson.sysml.LiteralString.class::cast)
                    .findFirst()
                    .ifPresentOrElse(
                        lit -> lit.setValue(kv.value()),
                        () -> {
                            // No LiteralString exists yet — create one
                            var lit = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createLiteralString();
                            lit.setValue(kv.value());
                            var membership = org.eclipse.syson.sysml.SysmlFactory.eINSTANCE.createFeatureValue();
                            membership.getOwnedRelatedElement().add(lit);
                            element.getOwnedRelationship().add(membership);
                        }
                    );
            }
        }
    }
}
