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
        if (endpoint == null || endpoint.isBlank()) {
            LOG.warn("LLM endpoint not configured; returning echo response");
            return "{\"message\": \"LLM endpoint not configured. Prompt was: " + escapeJson(userPrompt) + "\"}";
        }

        try {
            String body = buildRequestBody(systemPrompt, userPrompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
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

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        // Build an OpenAI-compatible chat completion request body
        return "{"
                + "\"model\": \"" + escapeJson(model) + "\","
                + "\"messages\": ["
                + "{\"role\": \"system\", \"content\": \"" + escapeJson(systemPrompt) + "\"},"
                + "{\"role\": \"user\", \"content\": \"" + escapeJson(userPrompt) + "\"}"
                + "],"
                + "\"temperature\": 0.7"
                + "}";
    }

    private String extractContent(String responseBody) {
        // Simple extraction of content from OpenAI-compatible response
        // In production, use a JSON parser; this is a minimal implementation
        String marker = "\"content\":\"";
        int idx = responseBody.indexOf(marker);
        if (idx < 0) {
            marker = "\"content\": \"";
            idx = responseBody.indexOf(marker);
        }
        if (idx >= 0) {
            int start = idx + marker.length();
            int end = responseBody.indexOf("\"", start);
            if (end > start) {
                return responseBody.substring(start, end)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
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
}
