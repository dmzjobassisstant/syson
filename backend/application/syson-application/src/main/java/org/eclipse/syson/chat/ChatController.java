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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.TenantContext;
import org.eclipse.syson.chat.dto.ChatExecuteRequest;
import org.eclipse.syson.chat.dto.ChatGenerateRequest;
import org.eclipse.syson.chat.dto.ChatModifyRequest;
import org.eclipse.syson.chat.dto.ChatProcessRequest;
import org.eclipse.syson.chat.dto.ChatResponse;
import org.eclipse.syson.chat.dto.ConversationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for LLM-powered chat operations on SysML models.
 *
 * @author syson-team
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Ensures the current request is authenticated.
     *
     * @return the authenticated user's UUID
     */
    private UUID requireAuthenticatedUser() {
        UUID userId = TenantContext.getUserIdAsUuid();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userId;
    }

    /**
     * POST /api/v1/projects/{projectId}/chat/process
     * <p>
     * Sends a prompt to the configured LLM and lets the structured system prompt
     * determine whether to return .sysml source, a library, or Sirius commands.
     */
    @PostMapping("/process")
    public ResponseEntity<ChatResponse> processPrompt(
            @PathVariable String projectId,
            @RequestBody ChatProcessRequest request) {
        UUID userId = requireAuthenticatedUser();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project ID format");
        }
        try {
            ChatResponse response = chatService.processPrompt(projectUuid, request, userId);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            LOG.error("LLM call failed for structured chat on project {}", projectId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM service unavailable: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/projects/{projectId}/chat/generate
     * <p>
     * Generates a SysML model from a natural-language prompt.
     */
    @PostMapping("/generate")
    public ResponseEntity<ChatResponse> generateModel(
            @PathVariable String projectId,
            @RequestBody ChatGenerateRequest request) {
        UUID userId = requireAuthenticatedUser();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project ID format");
        }
        try {
            ChatResponse response = chatService.generateModel(projectUuid, request, userId);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            LOG.error("LLM call failed for generate on project {}", projectId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM service unavailable: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/projects/{projectId}/chat/modify
     * <p>
     * Proposes modifications to an existing SysML model.
     */
    @PostMapping("/modify")
    public ResponseEntity<ChatResponse> modifyModel(
            @PathVariable String projectId,
            @RequestBody ChatModifyRequest request) {
        UUID userId = requireAuthenticatedUser();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project ID format");
        }
        try {
            ChatResponse response = chatService.modifyModel(projectUuid, request, userId);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            LOG.error("LLM call failed for modify on project {}", projectId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM service unavailable: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/projects/{projectId}/chat/execute
     * <p>
     * Executes approved changes on a SysML model.
     */
    @PostMapping("/execute")
    public ResponseEntity<ChatResponse> executeChanges(
            @PathVariable String projectId,
            @RequestBody ChatExecuteRequest request) {
        UUID userId = requireAuthenticatedUser();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project ID format");
        }
        try {
            ChatResponse response = chatService.executeChanges(
                    projectUuid, request.conversationId(), request.branchId(), request.changes(), userId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.error("Change execution failed for project {}", projectId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Change execution failed: " + e.getMessage());
        }
    }

    /**
     * GET /api/v1/projects/{projectId}/chat/conversations
     * <p>
     * Lists all chat conversations for a project.
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDto>> listConversations(@PathVariable String projectId) {
        requireAuthenticatedUser();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project ID format");
        }
        List<ConversationDto> conversations = chatService.listConversations(projectUuid);
        return ResponseEntity.ok(conversations);
    }
}
