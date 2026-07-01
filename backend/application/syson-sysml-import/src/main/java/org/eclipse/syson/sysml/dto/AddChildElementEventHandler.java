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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
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
import org.eclipse.syson.services.ElementInitializerSwitch;
import org.eclipse.syson.sysml.Element;
import org.eclipse.syson.sysml.OwningMembership;
import org.eclipse.syson.sysml.Relationship;
import org.eclipse.syson.sysml.SysmlFactory;
import org.eclipse.syson.sysml.util.ElementUtil;
import org.eclipse.syson.util.SysMLMetamodelHelper;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Sinks.Many;
import reactor.core.publisher.Sinks.One;

/**
 * Event handler for the addChildElement mutation.
 * <p>
 * Creates a new element of the given type under the specified parent element.
 * Handles intermediate containers (OwningMembership) exactly as the diagram
 * palette tools do. Works without a collaborative representation subscription.
 * </p>
 *
 * @author syson-team
 */
@Service
public class AddChildElementEventHandler implements IEditingContextEventHandler {

    private final IObjectSearchService objectSearchService;

    private final ICollaborativeMessageService messageService;

    private final Counter counter;

    public AddChildElementEventHandler(IObjectSearchService objectSearchService,
            ICollaborativeMessageService messageService, MeterRegistry meterRegistry) {
        this.objectSearchService = Objects.requireNonNull(objectSearchService);
        this.messageService = Objects.requireNonNull(messageService);
        this.counter = Counter.builder(Monitoring.EVENT_HANDLER)
                .tag(Monitoring.NAME, this.getClass().getSimpleName())
                .register(meterRegistry);
    }

    @Override
    public boolean canHandle(IEditingContext editingContext, IInput input) {
        return input instanceof AddChildElementInput;
    }

    @Override
    public void handle(One<IPayload> payloadSink, Many<ChangeDescription> changeDescriptionSink,
            IEditingContext editingContext, IInput input) {
        this.counter.increment();

        List<Message> messages = List.of(new Message(
                this.messageService.invalidInput(input.getClass().getSimpleName(), AddChildElementInput.class.getSimpleName()),
                MessageLevel.ERROR));
        ChangeDescription changeDescription = new ChangeDescription(ChangeKind.NOTHING, editingContext.getId(), input);
        IPayload payload = null;

        if (input instanceof AddChildElementInput addChildInput
                && editingContext instanceof IEMFEditingContext emfEditingContext) {

            Element parent = this.findElement(addChildInput.parentElementId(), emfEditingContext);
            EClass eClass = SysMLMetamodelHelper.toEClass(addChildInput.elementType());

            if (parent == null) {
                messages = List.of(new Message("Parent element not found: " + addChildInput.parentElementId(), MessageLevel.ERROR));
            } else if (eClass == null) {
                messages = List.of(new Message("Unknown element type: " + addChildInput.elementType()
                        + ". Valid types: Package, PartUsage, PartDefinition, AttributeUsage, AttributeDefinition, etc.", MessageLevel.ERROR));
            } else {
                EObject newObject = SysmlFactory.eINSTANCE.create(eClass);
                this.attachToParent(parent, newObject);

                // Clear imported flag and initialize defaults
                ElementUtil.setIsImported(newObject.eResource(), false);
                if (newObject instanceof Element newElement) {
                    new ElementInitializerSwitch().doSwitch(newElement);
                    // Override name if provided
                    if (addChildInput.name() != null && !addChildInput.name().isBlank()) {
                        newElement.setDeclaredName(addChildInput.name());
                    }
                }

                payload = new SuccessPayload(input.id(), List.of());
                changeDescription = new ChangeDescription(ChangeKind.SEMANTIC_CHANGE, editingContext.getId(), input);
            }
        }

        if (payload == null) {
            payload = new ErrorPayload(input.id(), messages);
        }

        payloadSink.tryEmitValue(payload);
        changeDescriptionSink.tryEmitNext(changeDescription);
    }

    /**
     * Attaches a newly-created EObject to its parent element.
     * Non-relationship elements need an intermediate OwningMembership container.
     * Relationships attach directly to getOwnedRelationship().
     */
    private void attachToParent(Element parent, EObject newObject) {
        if (newObject instanceof Relationship relationship) {
            parent.getOwnedRelationship().add(relationship);
        } else if (newObject instanceof Element newElement) {
            OwningMembership membership = SysmlFactory.eINSTANCE.createOwningMembership();
            membership.getOwnedRelatedElement().add(newElement);
            parent.getOwnedRelationship().add(membership);
        }
    }

    private Element findElement(String elementId, IEMFEditingContext emfEditingContext) {
        var optObject = this.objectSearchService.getObject(emfEditingContext, elementId);
        if (optObject.isPresent() && optObject.get() instanceof Element element) {
            return element;
        }
        return null;
    }
}
