/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import java.util.List;

import org.springframework.graphql.execution.TypeDefinitionConfigurer;
import org.springframework.stereotype.Component;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Patches the upstream {@code projectTemplates} field on {@code Viewer} to add
 * the optional {@code context: ProjectTemplateContext} argument.
 *
 * <p>The bundled Sirius Web 2025.6.1 frontend sends two
 * {@code getProjectTemplates} queries — one with a {@code $context} variable
 * and one without.  The upstream schema only defines
 * {@code projectTemplates(page: Int!, limit: Int!)} without the context arg.
 * We add the enum and optional argument here at registry-build time.</p>
 *
 * @author Damuza Consulting
 */
@Component
public class ProjectTemplateContextSchemaPatcher implements TypeDefinitionConfigurer {

    private static final String VIEWER = "Viewer";
    private static final String PROJECT_TEMPLATES = "projectTemplates";
    private static final String ALL_PROJECT_TEMPLATES = "allProjectTemplates";
    private static final String CONTEXT = "context";
    private static final String PROJECT_TEMPLATE = "ProjectTemplate";
    private static final String PROJECT_TEMPLATE_CONTEXT = "ProjectTemplateContext";
    private static final String CREATE_PROJECT_INPUT = "CreateProjectInput";
    private static final String NATURES = "natures";
    private static final String DIAGRAM_DESCRIPTION = "DiagramDescription";
    private static final String NODE_DESCRIPTIONS = "nodeDescriptions";
    private static final String DROP_NODE_COMPATIBILITY = "dropNodeCompatibility";

    @Override
    public void configure(TypeDefinitionRegistry registry) {
        addProjectTemplateContextEnum(registry);
        addContextArgumentToProjectTemplates(registry);
        addAllProjectTemplatesField(registry);
        makeCreateProjectNaturesOptional(registry);
        relaxDiagramDescriptionFrontendCompatibilityFields(registry);
    }

    private void addProjectTemplateContextEnum(TypeDefinitionRegistry registry) {
        if (registry.getType(PROJECT_TEMPLATE_CONTEXT).isPresent()) {
            return; // already defined
        }
        EnumTypeDefinition enumDef = EnumTypeDefinition.newEnumTypeDefinition()
                .name(PROJECT_TEMPLATE_CONTEXT)
                .enumValueDefinitions(List.of(
                        new EnumValueDefinition("PROJECT_BROWSER"),
                        new EnumValueDefinition("PROJECT_TEMPLATE_MODAL")))
                .build();
        registry.add(enumDef);
    }

    private void addContextArgumentToProjectTemplates(TypeDefinitionRegistry registry) {
        registry.getType(VIEWER, ObjectTypeDefinition.class).ifPresent(viewerType -> {
            ObjectTypeDefinition patchedViewer = viewerType.transform(builder -> builder.fieldDefinitions(patchProjectTemplatesFields(viewerType.getFieldDefinitions())));
            if (patchedViewer != viewerType) {
                registry.remove(viewerType);
                registry.add(patchedViewer);
            }
        });

        List<ObjectTypeExtensionDefinition> viewerExtensions = registry.objectTypeExtensions().getOrDefault(VIEWER, List.of());
        for (ObjectTypeExtensionDefinition extension : List.copyOf(viewerExtensions)) {
            ObjectTypeExtensionDefinition patchedExtension = extension.transformExtension(builder -> builder.fieldDefinitions(patchProjectTemplatesFields(extension.getFieldDefinitions())));
            if (patchedExtension != extension && !patchedExtension.getFieldDefinitions().equals(extension.getFieldDefinitions())) {
                registry.remove(VIEWER, extension);
                registry.add(patchedExtension);
            }
        }
    }

    private List<FieldDefinition> patchProjectTemplatesFields(List<FieldDefinition> fields) {
        return fields.stream()
                .map(field -> {
                    if (PROJECT_TEMPLATES.equals(field.getName())
                            && field.getInputValueDefinitions().stream()
                                    .noneMatch(arg -> CONTEXT.equals(arg.getName()))) {
                        InputValueDefinition contextArg = InputValueDefinition.newInputValueDefinition()
                                .name(CONTEXT)
                                .type(new TypeName(PROJECT_TEMPLATE_CONTEXT))
                                .build();
                        List<InputValueDefinition> newArgs = new java.util.ArrayList<>(field.getInputValueDefinitions());
                        newArgs.add(contextArg);
                        return field.transform(builder -> builder.inputValueDefinitions(newArgs));
                    }
                    return field;
                })
                .toList();
    }

    private void addAllProjectTemplatesField(TypeDefinitionRegistry registry) {
        boolean alreadyDefined = registry.getType(VIEWER, ObjectTypeDefinition.class)
                .map(viewer -> hasField(viewer.getFieldDefinitions(), ALL_PROJECT_TEMPLATES))
                .orElse(false)
                || registry.objectTypeExtensions().getOrDefault(VIEWER, List.of()).stream()
                        .anyMatch(extension -> hasField(extension.getFieldDefinitions(), ALL_PROJECT_TEMPLATES));
        if (alreadyDefined) {
            return;
        }

        FieldDefinition allProjectTemplatesField = FieldDefinition.newFieldDefinition()
                .name(ALL_PROJECT_TEMPLATES)
                .type(new NonNullType(new ListType(new NonNullType(new TypeName(PROJECT_TEMPLATE)))))
                .build();

        ObjectTypeExtensionDefinition extension = ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition()
                .name(VIEWER)
                .fieldDefinition(allProjectTemplatesField)
                .build();
        registry.add(extension);
    }

    private boolean hasField(List<FieldDefinition> fields, String fieldName) {
        return fields.stream().anyMatch(field -> fieldName.equals(field.getName()));
    }

    private void makeCreateProjectNaturesOptional(TypeDefinitionRegistry registry) {
        registry.getType(CREATE_PROJECT_INPUT, InputObjectTypeDefinition.class).ifPresent(inputType -> {
            List<InputValueDefinition> patchedFields = inputType.getInputValueDefinitions().stream()
                    .map(field -> {
                        if (NATURES.equals(field.getName()) && field.getType() instanceof NonNullType nonNullType) {
                            return field.transform(builder -> builder.type(nonNullType.getType()));
                        }
                        return field;
                    })
                    .toList();
            if (!patchedFields.equals(inputType.getInputValueDefinitions())) {
                InputObjectTypeDefinition patchedInput = inputType.transform(builder -> builder.inputValueDefinitions(patchedFields));
                registry.remove(inputType);
                registry.add(patchedInput);
            }
        });
    }

    private void relaxDiagramDescriptionFrontendCompatibilityFields(TypeDefinitionRegistry registry) {
        registry.getType(DIAGRAM_DESCRIPTION, ObjectTypeDefinition.class).ifPresent(diagramType -> {
            List<FieldDefinition> patchedFields = relaxDiagramDescriptionFields(diagramType.getFieldDefinitions());
            if (!patchedFields.equals(diagramType.getFieldDefinitions())) {
                ObjectTypeDefinition patchedDiagramType = diagramType.transform(builder -> builder.fieldDefinitions(patchedFields));
                registry.remove(diagramType);
                registry.add(patchedDiagramType);
            }
        });

        List<ObjectTypeExtensionDefinition> diagramExtensions = registry.objectTypeExtensions().getOrDefault(DIAGRAM_DESCRIPTION, List.of());
        for (ObjectTypeExtensionDefinition extension : List.copyOf(diagramExtensions)) {
            List<FieldDefinition> patchedFields = relaxDiagramDescriptionFields(extension.getFieldDefinitions());
            if (!patchedFields.equals(extension.getFieldDefinitions())) {
                ObjectTypeExtensionDefinition patchedExtension = extension.transformExtension(builder -> builder.fieldDefinitions(patchedFields));
                registry.remove(DIAGRAM_DESCRIPTION, extension);
                registry.add(patchedExtension);
            }
        }
    }

    private List<FieldDefinition> relaxDiagramDescriptionFields(List<FieldDefinition> fields) {
        return fields.stream()
                .map(field -> {
                    if ((NODE_DESCRIPTIONS.equals(field.getName()) || DROP_NODE_COMPATIBILITY.equals(field.getName()))
                            && field.getType() instanceof NonNullType nonNullType) {
                        return field.transform(builder -> builder.type(nonNullType.getType()));
                    }
                    return field;
                })
                .toList();
    }
}
