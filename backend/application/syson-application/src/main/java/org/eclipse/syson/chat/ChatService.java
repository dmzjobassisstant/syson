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
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.chat;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.chat.dto.ChatGenerateRequest;
import org.eclipse.syson.chat.dto.ChatModifyRequest;
import org.eclipse.syson.chat.dto.ChatResponse;
import org.eclipse.syson.chat.dto.ValidateResponse;
import org.eclipse.syson.chat.entity.ChatConversationEntity;
import org.eclipse.syson.chat.entity.ChatMessageEntity;
import org.eclipse.syson.chat.repository.ChatConversationRepository;
import org.eclipse.syson.chat.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the LLM chat flow for SysML model generation and modification.
 *
 * @author syson-team
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    static final String SYSTEM_PROMPT = """
You are a SysML v2 modeling assistant. Generate ONLY valid SysML v2 textual notation.

CRITICAL RULES:
1. Use OMG SysML v2 syntax.
2. Package: `package Name { ... }`
3. Part definitions: `part def Name { ... }`
4. Specialization: `part def Child :> Parent { ... }`
5. Ports: `in port name : Type;` (direction BEFORE `port`)
6. Enums: `enum def Name { literal Name1; literal Name2; }`
7. Requirements: `requirement def ReqId { doc /* text */ }`
8. Attributes: `attribute name : Type;`
9. Relationships: use OMG syntax (composition, specialization, satisfy)
10. Imports: `private import PackageName::*;`
11. Transitions: `transition t first A then B;`
12. Never generate: `actor`/`useCase` shorthand, `value Real;`, `view Name : kind { }`.

When modifying, return ONLY a JSON array:
[{"operation":"CREATE","parentId":"uuid","elementType":"part_def","name":"X","properties":{...}}]
""";

    private final LlmClientService llmClientService;
    private final ModelSerializationService modelSerializationService;
    private final SysmlSyntaxValidator sysmlSyntaxValidator;
    private final ChangeExecutionService changeExecutionService;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    public ChatService(LlmClientService llmClientService,
                       ModelSerializationService modelSerializationService,
                       SysmlSyntaxValidator sysmlSyntaxValidator,
                       ChangeExecutionService changeExecutionService,
                       ChatConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository) {
        this.llmClientService = llmClientService;
        this.modelSerializationService = modelSerializationService;
        this.sysmlSyntaxValidator = sysmlSyntaxValidator;
        this.changeExecutionService = changeExecutionService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * Generates a SysML model from a natural-language prompt.
     */
    @Transactional
    public ChatResponse generateModel(UUID projectId, ChatGenerateRequest request, UUID userId) throws IOException {
        UUID conversationId = getOrCreateConversation(projectId, request.conversationId(), request.prompt(), userId);

        String userPrompt = "Generate a SysML v2 model for: " + request.prompt();
        if (request.loadAsLibrary()) {
            userPrompt += " This model should be structured as a reusable library.";
        }

        String llmOutput = llmClientService.chat(SYSTEM_PROMPT, userPrompt);

        // Validate the generated SysML
        ValidateResponse validationResult = sysmlSyntaxValidator.validate(llmOutput, null);

        // Store the assistant message
        saveMessage(conversationId, "user", userPrompt, null);
        saveMessage(conversationId, "assistant", llmOutput, null);

        return new ChatResponse(conversationId, "Model generated successfully.", llmOutput,
                validationResult, List.of(), false, null);
    }

    /**
     * Modifies an existing SysML model based on a prompt.
     */
    @Transactional
    public ChatResponse modifyModel(UUID projectId, ChatModifyRequest request, UUID userId) throws IOException {
        UUID conversationId = getOrCreateConversation(projectId, request.conversationId(), request.prompt(), userId);

        // Serialize the current model
        String currentModel = modelSerializationService.serializeCurrentModel(projectId, request.branchId());

        String userPrompt = "Current model:\n```sysml\n" + currentModel + "\n```\n\nModification request: " + request.prompt()
                + "\n\nReturn ONLY a JSON array of change operations.";

        String llmOutput = llmClientService.chat(SYSTEM_PROMPT, userPrompt);

        // Validate the output (could be SysML text or JSON changes)
        ValidateResponse validationResult = sysmlSyntaxValidator.validate(llmOutput, null);

        saveMessage(conversationId, "user", request.prompt(), null);
        saveMessage(conversationId, "assistant", llmOutput, null);

        return new ChatResponse(conversationId, "Modification proposed.", llmOutput,
                validationResult, List.of(), false, null);
    }

    /**
     * Executes approved changes on the model.
     */
    @Transactional
    public ChatResponse executeChanges(UUID projectId, UUID conversationId, UUID branchId,
                                        List<org.eclipse.syson.chat.dto.ChangeOperation> changes, UUID userId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }

        // Delegate to ChangeExecutionService
        String result = changeExecutionService.executeChanges(projectId, branchId, changes);

        saveMessage(conversationId, "system", "Changes executed: " + result, null);

        return new ChatResponse(conversationId, "Changes executed.", null,
                null, changes, true, null);
    }

    /**
     * Lists conversations for a project.
     */
    public List<org.eclipse.syson.chat.dto.ConversationDto> listConversations(UUID projectId) {
        List<ChatConversationEntity> conversations = conversationRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
        List<org.eclipse.syson.chat.dto.ConversationDto> dtos = new ArrayList<>();
        for (ChatConversationEntity conv : conversations) {
            int messageCount = (int) messageRepository.countByConversationId(conv.getId());
            dtos.add(new org.eclipse.syson.chat.dto.ConversationDto(
                    conv.getId(), conv.getTitle(), conv.getCreatedAt(), messageCount));
        }
        return dtos;
    }

    private UUID getOrCreateConversation(UUID projectId, UUID conversationId, String title, UUID userId) {
        if (conversationId != null) {
            return conversationId;
        }
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setProjectId(projectId);
        conv.setTitle(truncate(title, 500));
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        conv.setCreatedBy(userId);
        conv = conversationRepository.save(conv);
        return conv.getId();
    }

    private void saveMessage(UUID conversationId, String role, String content, String changes) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setChanges(changes);
        msg.setExecuted(false);
        msg.setCreatedAt(OffsetDateTime.now());
        messageRepository.save(msg);

        // Update conversation updated_at
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            conv.setUpdatedAt(OffsetDateTime.now());
            conversationRepository.save(conv);
        });
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "Untitled";
        }
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
