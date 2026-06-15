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

import org.eclipse.syson.chat.ChatStructuredOutputParser.ParsedChatOutput;
import org.eclipse.syson.chat.dto.ChatGenerateRequest;
import org.eclipse.syson.chat.dto.ChatModifyRequest;
import org.eclipse.syson.chat.dto.ChatProcessRequest;
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
You are the SysMLv2 Architect/SysON modeling assistant. The client UI has no action-mode buttons.
You MUST infer the required action from the user's words and return exactly one XML wrapper rooted at <syson-response>.
No markdown fences. No prose outside the XML.

Always include a user-facing progress/status node first:
<chat_feedback verbosity="progress|done|error">Short verbose text for the chat: what you are doing, what was created, or what needs review.</chat_feedback>

Then include exactly ONE of these three action structures:

1) New reusable SysML library to load/import as .sysml:
<sysml_library name="LibraryName" filename="LibraryName.sysml"><![CDATA[
package LibraryName {
  private import ScalarValues::*;
  part def Example { attribute mass : Real; }
}
]]></sysml_library>

2) New full SysML model to load as .sysml:
<sysml_model name="ModelName" filename="ModelName.sysml"><![CDATA[
package ModelName {
  private import ScalarValues::*;
  part def System { }
}
]]></sysml_model>

3) Existing model modification as a sequence of Sirius Web command operations:
<sirius_commands target="existing-model">
  <command action="CREATE" elementType="part_def" name="FanAssembly" parentId="optional-uuid">
    <property name="description" value="Cooling fan assembly"/>
  </command>
  <command action="CREATE" elementType="port" name="airIn" parentId="optional-uuid"/>
  <command action="UPDATE" elementType="part_def" targetId="optional-uuid">
    <property name="name" value="UpdatedName"/>
  </command>
</sirius_commands>

Detection policy:
- If the user asks for a reusable library, standard catalog, importable definitions, or "load as library", return <sysml_library>.
- If the user asks to create/generate/build a whole new model, return <sysml_model>.
- If the user asks to modify/add/remove/update something in the current model, return <sirius_commands>.

SysML generation rules:
1. Use OMG SysML v2 syntax.
2. Package: `package Name { ... }`
3. Part definitions: `part def Name { ... }`
4. Specialization: `part def Child :> Parent { ... }`
5. Ports: `in port name : Type;` or `out port name : Type;`
6. Enums: `enum def Name { enum literal one; enum literal two; }`
7. Requirements: `requirement def ReqId { doc /* text */ }`
8. Attributes: `attribute name : Real;` only with `private import ScalarValues::*;`.
9. Do not generate: `actor`/`useCase` shorthand, `value Real;`, or unverified `view Name : kind { }`.
""";

    private final LlmClientService llmClientService;
    private final ModelSerializationService modelSerializationService;
    private final SysmlSyntaxValidator sysmlSyntaxValidator;
    private final ChangeExecutionService changeExecutionService;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatStructuredOutputParser structuredOutputParser;

    public ChatService(LlmClientService llmClientService,
                       ModelSerializationService modelSerializationService,
                       SysmlSyntaxValidator sysmlSyntaxValidator,
                       ChangeExecutionService changeExecutionService,
                       ChatConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       ChatStructuredOutputParser structuredOutputParser) {
        this.llmClientService = llmClientService;
        this.modelSerializationService = modelSerializationService;
        this.sysmlSyntaxValidator = sysmlSyntaxValidator;
        this.changeExecutionService = changeExecutionService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.structuredOutputParser = structuredOutputParser;
    }

    /**
     * Processes a prompt through the structured LLM contract. The LLM decides whether
     * the output is a SysML model, reusable library, or Sirius command sequence.
     */
    @Transactional
    public ChatResponse processPrompt(UUID projectId, ChatProcessRequest request, UUID userId) throws IOException {
        UUID conversationId = getOrCreateConversation(projectId, request.conversationId(), request.prompt(), userId);
        String currentModel = modelSerializationService.serializeCurrentModel(projectId, request.branchId());
        String userPrompt = "Project: " + projectId + "\n"
                + "Current branch: " + (request.branchId() == null ? "active/default" : request.branchId()) + "\n"
                + "Current model snapshot:\n```sysml\n" + currentModel + "\n```\n\n"
                + "User request: " + request.prompt() + "\n\n"
                + "Infer the action and return the required XML structure.";

        String llmOutput = llmClientService.chat(SYSTEM_PROMPT, userPrompt,
                request.apiEndpoint(), request.apiKey(), request.model());
        ParsedChatOutput parsed = structuredOutputParser.parse(llmOutput);

        ValidateResponse validationResult = null;
        if (parsed.sysmlText() != null && !parsed.sysmlText().isBlank()) {
            validationResult = sysmlSyntaxValidator.validate(parsed.sysmlText(), null);
        }

        saveMessage(conversationId, "user", request.prompt(), null);
        saveMessage(conversationId, "assistant", llmOutput, null);

        String feedback = parsed.feedback() == null || parsed.feedback().isBlank()
                ? defaultFeedback(parsed.type(), parsed.sysmlText(), parsed.changes().size())
                : parsed.feedback();
        return new ChatResponse(conversationId, feedback, parsed.sysmlText(),
                validationResult, parsed.changes(), false, null);
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

    private String defaultFeedback(String type, String sysmlText, int changeCount) {
        if (ChatStructuredOutputParser.TYPE_SYSML_LIBRARY.equals(type)) {
            return "Prepared a reusable SysML library with " + (sysmlText == null ? 0 : sysmlText.length()) + " characters of .sysml source.";
        }
        if (ChatStructuredOutputParser.TYPE_SYSML_MODEL.equals(type)) {
            return "Prepared a new SysML model with " + (sysmlText == null ? 0 : sysmlText.length()) + " characters of .sysml source.";
        }
        if (ChatStructuredOutputParser.TYPE_SIRIUS_COMMANDS.equals(type)) {
            return "Prepared " + changeCount + " Sirius command(s) for review before execution.";
        }
        return "No executable model action was returned.";
    }
}
