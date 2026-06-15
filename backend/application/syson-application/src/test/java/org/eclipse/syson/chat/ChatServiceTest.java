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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.chat.dto.ChatGenerateRequest;
import org.eclipse.syson.chat.dto.ChatModifyRequest;
import org.eclipse.syson.chat.dto.ChatProcessRequest;
import org.eclipse.syson.chat.dto.ChatResponse;
import org.eclipse.syson.chat.dto.ChangeOperation;
import org.eclipse.syson.chat.dto.ConversationDto;
import org.eclipse.syson.chat.entity.ChatConversationEntity;
import org.eclipse.syson.chat.repository.ChatConversationRepository;
import org.eclipse.syson.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link ChatService} covering the generate, modify, execute, and
 * conversation-listing flows with mocked LLM client.
 *
 * @author syson-team
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private LlmClientService llmClientService;

    @Mock
    private ModelSerializationService modelSerializationService;

    @Mock
    private SysmlSyntaxValidator sysmlSyntaxValidator;

    @Mock
    private ChangeExecutionService changeExecutionService;

    @Mock
    private ChatConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    private ChatService chatService;

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                llmClientService,
                modelSerializationService,
                sysmlSyntaxValidator,
                changeExecutionService,
                conversationRepository,
                messageRepository,
                new ChatStructuredOutputParser());
    }

    @Test
    void generateModel_shouldReturnValidResponse() throws Exception {
        String sysmlOutput = "package TestModel { part def Engine { } }";
        when(llmClientService.chat(anyString(), anyString())).thenReturn(sysmlOutput);
        when(sysmlSyntaxValidator.validate(anyString(), any()))
                .thenReturn(new org.eclipse.syson.chat.dto.ValidateResponse(true, List.of(), 0, 0));

        // Simulate repository save
        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        mockConv.setTitle("Test model");
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.generateModel(PROJECT_ID,
                new ChatGenerateRequest("Create an engine model", null, false), USER_ID);

        assertNotNull(response);
        assertNotNull(response.conversationId());
        assertEquals(sysmlOutput, response.sysmlText());
        assertFalse(response.executed());
        assertTrue(response.validationResult().valid());
    }

    @Test
    void generateModel_withLibraryFlag_shouldReturnResponse() throws Exception {
        String sysmlOutput = "package LibraryModel { part def Widget { } }";
        when(llmClientService.chat(anyString(), anyString())).thenReturn(sysmlOutput);
        when(sysmlSyntaxValidator.validate(anyString(), any()))
                .thenReturn(new org.eclipse.syson.chat.dto.ValidateResponse(true, List.of(), 0, 0));

        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.generateModel(PROJECT_ID,
                new ChatGenerateRequest("Create a widget library", null, true), USER_ID);

        assertNotNull(response);
        assertTrue(response.sysmlText().contains("LibraryModel"));
    }

    @Test
    void generateModel_withValidationErrors_shouldReturnErrors() throws Exception {
        String sysmlOutput = "invalid { {";
        when(llmClientService.chat(anyString(), anyString())).thenReturn(sysmlOutput);
        when(sysmlSyntaxValidator.validate(anyString(), any()))
                .thenReturn(new org.eclipse.syson.chat.dto.ValidateResponse(false,
                        List.of(new org.eclipse.syson.chat.dto.Diagnostic("error", 1, 1, "Unbalanced braces", "model.sysml")),
                        1, 0));

        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.generateModel(PROJECT_ID,
                new ChatGenerateRequest("Create a broken model", null, false), USER_ID);

        assertNotNull(response);
        assertFalse(response.validationResult().valid());
        assertEquals(1, response.validationResult().errorCount());
    }

    @Test
    void modifyModel_shouldIncludeCurrentModelInPrompt() throws Exception {
        String currentModel = "package Existing { part def OldPart { } }";
        String llmOutput = "[{\"operation\":\"CREATE\",\"elementType\":\"part_def\",\"name\":\"NewPart\"}]";

        when(modelSerializationService.serializeCurrentModel(PROJECT_ID, null))
                .thenReturn(currentModel);
        when(llmClientService.chat(anyString(), anyString())).thenReturn(llmOutput);
        when(sysmlSyntaxValidator.validate(anyString(), any()))
                .thenReturn(new org.eclipse.syson.chat.dto.ValidateResponse(true, List.of(), 0, 0));

        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.modifyModel(PROJECT_ID,
                new ChatModifyRequest("Add a new part", null, null), USER_ID);

        assertNotNull(response);
        assertTrue(response.sysmlText().contains("NewPart"));
    }

    @Test
    void listConversations_shouldReturnConversations() {
        ChatConversationEntity conv1 = new ChatConversationEntity();
        conv1.setId(UUID.randomUUID());
        conv1.setProjectId(PROJECT_ID);
        conv1.setTitle("First conversation");

        when(conversationRepository.findByProjectIdOrderByUpdatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(conv1));
        when(messageRepository.countByConversationId(conv1.getId())).thenReturn(3L);

        List<ConversationDto> conversations = chatService.listConversations(PROJECT_ID);

        assertNotNull(conversations);
        assertEquals(1, conversations.size());
        assertEquals("First conversation", conversations.get(0).title());
        assertEquals(3, conversations.get(0).messageCount());
    }

    @Test
    void processPrompt_shouldDetectSysmlModelFromStructuredResponse() throws Exception {
        String llmOutput = "<syson-response>"
                + "<chat_feedback verbosity=\"done\">Created the cooling fan model as .sysml.</chat_feedback>"
                + "<sysml_model name=\"CoolingFan\"><![CDATA[package CoolingFan { private import ScalarValues::*; part def FanAssembly { attribute diameter : Real; } }]]></sysml_model>"
                + "</syson-response>";
        when(modelSerializationService.serializeCurrentModel(PROJECT_ID, null)).thenReturn("");
        when(llmClientService.chat(anyString(), anyString(), any(), any(), any())).thenReturn(llmOutput);
        when(sysmlSyntaxValidator.validate(anyString(), any()))
                .thenReturn(new org.eclipse.syson.chat.dto.ValidateResponse(true, List.of(), 0, 0));
        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.processPrompt(PROJECT_ID,
                new ChatProcessRequest("Generate a cooling fan model", null, null, "http://llm", "key", "model"), USER_ID);

        assertEquals("Created the cooling fan model as .sysml.", response.message());
        assertTrue(response.sysmlText().contains("FanAssembly"));
        assertTrue(response.validationResult().valid());
    }

    @Test
    void processPrompt_shouldDetectSiriusCommandSequenceFromStructuredResponse() throws Exception {
        String llmOutput = "<syson-response>"
                + "<chat_feedback verbosity=\"progress\">Prepared updates to the fan model.</chat_feedback>"
                + "<sirius_commands target=\"existing-model\"><command action=\"CREATE\" elementType=\"part_def\" name=\"FanBlade\"/></sirius_commands>"
                + "</syson-response>";
        when(modelSerializationService.serializeCurrentModel(PROJECT_ID, null)).thenReturn("package Existing { }");
        when(llmClientService.chat(anyString(), anyString(), any(), any(), any())).thenReturn(llmOutput);
        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(UUID.randomUUID());
        mockConv.setProjectId(PROJECT_ID);
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(mockConv);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.processPrompt(PROJECT_ID,
                new ChatProcessRequest("Add blades to this model", null, null, "http://llm", "key", "model"), USER_ID);

        assertEquals("Prepared updates to the fan model.", response.message());
        assertEquals(1, response.changes().size());
        assertEquals("FanBlade", response.changes().get(0).name());
    }

    @Test
    void executeChanges_shouldReturnExecutedResponse() {
        UUID conversationId = UUID.randomUUID();
        List<ChangeOperation> changes = List.of(
                new ChangeOperation("CREATE", null, "part_def", "Engine", null, null, null, null, null));

        when(changeExecutionService.executeChanges(
                org.mockito.ArgumentMatchers.eq(PROJECT_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("Executed 1 changes: 1 created, 0 updated, 0 deleted.");

        ChatConversationEntity mockConv = new ChatConversationEntity();
        mockConv.setId(conversationId);
        when(conversationRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(mockConv));

        ChatResponse response = chatService.executeChanges(PROJECT_ID, conversationId, null, changes, USER_ID);

        assertNotNull(response);
        assertTrue(response.executed());
        assertEquals(conversationId, response.conversationId());
        assertEquals(1, response.changes().size());
    }
}
