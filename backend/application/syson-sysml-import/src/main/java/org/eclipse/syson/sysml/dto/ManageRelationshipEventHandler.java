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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.util.EcoreUtil;
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
import org.eclipse.syson.sysml.Dependency;
import org.eclipse.syson.sysml.Element;
import org.eclipse.syson.sysml.Namespace;
import org.eclipse.syson.sysml.Relationship;
import org.eclipse.syson.sysml.Specialization;
import org.eclipse.syson.sysml.Subclassification;
import org.eclipse.syson.sysml.SysmlFactory;
import org.eclipse.syson.sysml.Type;
import org.eclipse.syson.sysml.util.ElementUtil;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Sinks.Many;
import reactor.core.publisher.Sinks.One;

/**
 * Event handler for the manageRelationship mutation.
 * <p>
 * Creates or removes relationships between SysML elements. Supports:
 * <ul>
 *   <li>Dependency: source depends on targets (source=client, targets=suppliers)</li>
 *   <li>Subclassification: source is a subclass of targets (generalization)</li>
 *   <li>Specialization: generic specialization relationship</li>
 * </ul>
 * Works without a collaborative representation subscription.
 * </p>
 *
 * @author syson-team
 */
@Service
public class ManageRelationshipEventHandler implements IEditingContextEventHandler {

    private static final String DEPENDENCY = "Dependency";

    private static final String SUBCLASSIFICATION = "Subclassification";

    private static final String SPECIALIZATION = "Specialization";

    private final IObjectSearchService objectSearchService;

    private final ICollaborativeMessageService messageService;

    private final Counter counter;

    public ManageRelationshipEventHandler(IObjectSearchService objectSearchService,
            ICollaborativeMessageService messageService, MeterRegistry meterRegistry) {
        this.objectSearchService = Objects.requireNonNull(objectSearchService);
        this.messageService = Objects.requireNonNull(messageService);
        this.counter = Counter.builder(Monitoring.EVENT_HANDLER)
                .tag(Monitoring.NAME, this.getClass().getSimpleName())
                .register(meterRegistry);
    }

    @Override
    public boolean canHandle(IEditingContext editingContext, IInput input) {
        return input instanceof ManageRelationshipInput;
    }

    @Override
    public void handle(One<IPayload> payloadSink, Many<ChangeDescription> changeDescriptionSink,
            IEditingContext editingContext, IInput input) {
        this.counter.increment();

        List<Message> messages = List.of(new Message(
                this.messageService.invalidInput(input.getClass().getSimpleName(), ManageRelationshipInput.class.getSimpleName()),
                MessageLevel.ERROR));
        ChangeDescription changeDescription = new ChangeDescription(ChangeKind.NOTHING, editingContext.getId(), input);
        IPayload payload = null;

        if (input instanceof ManageRelationshipInput relInput
                && editingContext instanceof IEMFEditingContext emfEditingContext) {
            var result = this.processRelationship(relInput, emfEditingContext);
            if (result.success()) {
                payload = new SuccessPayload(input.id(), result.messages());
                changeDescription = new ChangeDescription(ChangeKind.SEMANTIC_CHANGE, editingContext.getId(), input);
            } else {
                messages = result.messages();
            }
        }

        if (payload == null) {
            payload = new ErrorPayload(input.id(), messages);
        }

        payloadSink.tryEmitValue(payload);
        changeDescriptionSink.tryEmitNext(changeDescription);
    }

    /**
     * Processes a relationship mutation request and returns the result.
     */
    private RelationshipResult processRelationship(ManageRelationshipInput relInput, IEMFEditingContext emfEditingContext) {
        RelationshipResult result = new RelationshipResult(false, List.of(
                new Message("Invalid action: " + relInput.action() + ". Must be ADD or REMOVE", MessageLevel.ERROR)));

        Element source = this.findElement(relInput.sourceElementId(), emfEditingContext);
        if (source != null) {
            if (relInput.targetElementIds() == null || relInput.targetElementIds().isEmpty()) {
                result = new RelationshipResult(false, List.of(
                        new Message("No target element IDs provided", MessageLevel.ERROR)));
            } else {
                List<Element> targets = relInput.targetElementIds().stream()
                        .map(tid -> this.findElement(tid, emfEditingContext))
                        .filter(Objects::nonNull)
                        .toList();

                if (targets.size() != relInput.targetElementIds().size()) {
                    result = new RelationshipResult(false, List.of(
                            new Message("One or more target elements not found", MessageLevel.ERROR)));
                } else if (ManageRelationshipInput.ADD.equals(relInput.action())) {
                    result = this.doAdd(source, targets, relInput.relationshipType());
                } else if (ManageRelationshipInput.REMOVE.equals(relInput.action())) {
                    result = this.doRemove(source, targets, relInput.relationshipType());
                }
            }
        } else {
            result = new RelationshipResult(false, List.of(
                    new Message("Source element not found: " + relInput.sourceElementId(), MessageLevel.ERROR)));
        }
        return result;
    }

    /**
     * Adds a relationship between source and targets.
     */
    private RelationshipResult doAdd(Element source, List<Element> targets, String relationshipType) {
        boolean created = this.addRelationship(source, targets, relationshipType);
        if (created) {
            return new RelationshipResult(true, List.of());
        }
        return new RelationshipResult(false, List.of(
                new Message("Unsupported relationship type: " + relationshipType
                        + ". Supported: Dependency, Subclassification, Specialization", MessageLevel.ERROR)));
    }

    /**
     * Removes matching relationships between source and targets.
     */
    private RelationshipResult doRemove(Element source, List<Element> targets, String relationshipType) {
        int removed = this.removeRelationship(source, targets, relationshipType);
        return new RelationshipResult(true, List.of(
                new Message("Removed " + removed + " " + relationshipType + "(s)", MessageLevel.SUCCESS)));
    }

    /**
     * Creates and attaches a relationship between source and target elements.
     */
    private boolean addRelationship(Element source, List<Element> targets, String relationshipType) {
        return switch (relationshipType) {
            case DEPENDENCY -> this.addDependency(source, targets);
            case SUBCLASSIFICATION -> this.addSubclassification(source, targets);
            case SPECIALIZATION -> this.addSpecialization(source, targets);
            default -> false;
        };
    }

    private boolean addDependency(Element source, List<Element> targets) {
        Dependency dependency = SysmlFactory.eINSTANCE.createDependency();
        dependency.getClient().add(source);
        dependency.getSupplier().addAll(targets);
        Namespace owningNamespace = source.getOwningNamespace();
        if (owningNamespace == null && source instanceof Namespace ns) {
            owningNamespace = ns;
        }
        if (owningNamespace != null) {
            owningNamespace.getOwnedRelationship().add(dependency);
        } else {
            source.getOwnedRelationship().add(dependency);
        }
        ElementUtil.setIsImported(source.eResource(), false);
        return true;
    }

    private boolean addSubclassification(Element source, List<Element> targets) {
        if (!(source instanceof Type sourceType)) {
            return false;
        }
        for (Element target : targets) {
            if (target instanceof Type targetType) {
                Subclassification sub = SysmlFactory.eINSTANCE.createSubclassification();
                sub.setSpecific(sourceType);
                sub.setGeneral(targetType);
                source.getOwnedRelationship().add(sub);
            }
        }
        ElementUtil.setIsImported(source.eResource(), false);
        return true;
    }

    private boolean addSpecialization(Element source, List<Element> targets) {
        if (!(source instanceof Type sourceType)) {
            return false;
        }
        for (Element target : targets) {
            if (target instanceof Type targetType) {
                Specialization spec = SysmlFactory.eINSTANCE.createSpecialization();
                spec.setSpecific(sourceType);
                spec.setGeneral(targetType);
                source.getOwnedRelationship().add(spec);
            }
        }
        ElementUtil.setIsImported(source.eResource(), false);
        return true;
    }

    /**
     * Removes matching relationships between source and targets.
     */
    private int removeRelationship(Element source, List<Element> targets, String relationshipType) {
        List<Relationship> toRemove = new ArrayList<>();
        for (Element target : targets) {
            this.collectRelationshipsToRemove(source, target, relationshipType, toRemove);
        }
        for (Relationship rel : toRemove) {
            EcoreUtil.delete(rel, true);
        }
        return toRemove.size();
    }

    private void collectRelationshipsToRemove(Element source, Element target, String relationshipType,
            List<Relationship> toRemove) {
        switch (relationshipType) {
            case DEPENDENCY -> this.collectDependencies(source, target, toRemove);
            case SUBCLASSIFICATION -> source.getOwnedRelationship().stream()
                    .filter(Subclassification.class::isInstance)
                    .map(Subclassification.class::cast)
                    .filter(s -> s.getGeneral() == target && s.getSpecific() == source)
                    .forEach(toRemove::add);
            case SPECIALIZATION -> source.getOwnedRelationship().stream()
                    .filter(Specialization.class::isInstance)
                    .map(Specialization.class::cast)
                    .filter(s -> s.getGeneral() == target && s.getSpecific() == source)
                    .forEach(toRemove::add);
            default -> { }
        }
    }

    private void collectDependencies(Element source, Element target, List<Relationship> toRemove) {
        source.getOwnedRelationship().stream()
                .filter(Dependency.class::isInstance)
                .map(Dependency.class::cast)
                .filter(d -> d.getClient().contains(source) && d.getSupplier().contains(target))
                .forEach(toRemove::add);
        Namespace ns = source.getOwningNamespace();
        if (ns != null) {
            ns.getOwnedRelationship().stream()
                    .filter(Dependency.class::isInstance)
                    .map(Dependency.class::cast)
                    .filter(d -> d.getClient().contains(source) && d.getSupplier().contains(target))
                    .forEach(toRemove::add);
        }
    }

    private Element findElement(String elementId, IEMFEditingContext emfEditingContext) {
        var optObject = this.objectSearchService.getObject(emfEditingContext, elementId);
        if (optObject.isPresent() && optObject.get() instanceof Element element) {
            return element;
        }
        return null;
    }

    /**
     * Internal result record for relationship processing.
     */
    private record RelationshipResult(boolean success, List<Message> messages) {
    }
}
