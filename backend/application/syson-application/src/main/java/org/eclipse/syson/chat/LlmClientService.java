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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Low-level HTTP client for communicating with an LLM API endpoint.
 * <p>
 * Configuration is read from application properties:
 * <ul>
 *   <li>{@code syson.llm.endpoint} – the LLM API endpoint URL</li>
 *   <li>{@code syson.llm.api-key} – the API key for authentication</li>
 *   <li>{@code syson.llm.model} – the model name to use</li>
 * </ul>
 *
 * @author syson-team
 */
@Service
public class LlmClientService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmClientService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public LlmClientService(
            @Value("${syson.llm.endpoint:}") String endpoint,
            @Value("${syson.llm.api-key:}") String apiKey,
            @Value("${syson.llm.model:}") String model) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Sends a chat completion request to the configured LLM endpoint.
     *
     * @param systemPrompt the system-level instruction
     * @param userPrompt   the user's request
     * @return the LLM response text
     * @throws IOException if the HTTP call fails
     */
    public String chat(String systemPrompt, String userPrompt) throws IOException {
        return chat(systemPrompt, userPrompt, null, null, null);
    }

    /**
     * Sends a chat completion request with optional per-request client overrides.
     */
    public String chat(String systemPrompt, String userPrompt, String endpointOverride, String apiKeyOverride, String modelOverride) throws IOException {
        String effectiveEndpoint = firstNonBlank(endpointOverride, endpoint);
        String effectiveApiKey = firstNonBlank(apiKeyOverride, apiKey);
        String effectiveModel = firstNonBlank(modelOverride, model);
        if (effectiveEndpoint == null || effectiveEndpoint.isBlank()) {
            LOG.warn("LLM endpoint not configured; returning echo response");
            return "<syson-response><chat_feedback>LLM endpoint not configured. Prompt was: " + escapeXml(userPrompt) + "</chat_feedback></syson-response>";
        }

        try {
            String body = buildRequestBody(systemPrompt, userPrompt, effectiveModel);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveEndpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return extractContent(response.body());
            } else {
                LOG.error("LLM API returned status {}: {}", response.statusCode(), response.body());
                throw new IOException("LLM API returned status " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM request interrupted", e);
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt, String effectiveModel) {
        // Build an OpenAI-compatible chat completion request body
        return "{"
                + "\"model\": \"" + escapeJson(effectiveModel) + "\","
                + "\"messages\": ["
                + "{\"role\": \"system\", \"content\": \"" + escapeJson(systemPrompt) + "\"},"
                + "{\"role\": \"user\", \"content\": \"" + escapeJson(userPrompt) + "\"}"
                + "],"
                + "\"temperature\": 0.7"
                + "}";
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
                JsonNode text = choices.get(0).path("text");
                if (text.isTextual()) {
                    return text.asText();
                }
            }
            JsonNode content = root.path("content");
            if (content.isTextual()) {
                return content.asText();
            }
        } catch (Exception e) {
            LOG.debug("LLM response was not JSON or did not match OpenAI-compatible shape", e);
        }
        return responseBody;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String firstNonBlank(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        return fallback;
    }
}
