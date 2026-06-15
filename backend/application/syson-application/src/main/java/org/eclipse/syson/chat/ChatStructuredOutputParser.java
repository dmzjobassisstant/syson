/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.syson.chat.dto.ChangeOperation;
import org.springframework.stereotype.Service;

/**
 * Parses the structured LLM contract used by the SysON chat UI.
 */
@Service
public class ChatStructuredOutputParser {

    public static final String TYPE_SYSML_MODEL = "SYSML_MODEL";
    public static final String TYPE_SYSML_LIBRARY = "SYSML_LIBRARY";
    public static final String TYPE_SIRIUS_COMMANDS = "SIRIUS_COMMANDS";
    public static final String TYPE_FEEDBACK_ONLY = "FEEDBACK_ONLY";

    private static final Pattern FEEDBACK = Pattern.compile("<chat_feedback(?:\\s+[^>]*)?>(.*?)</chat_feedback>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSML_MODEL = Pattern.compile("<sysml_model(?:\\s+[^>]*)?>(.*?)</sysml_model>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSML_LIBRARY = Pattern.compile("<sysml_library(?:\\s+[^>]*)?>(.*?)</sysml_library>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMANDS = Pattern.compile("<sirius_commands(?:\\s+[^>]*)?>(.*?)</sirius_commands>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND = Pattern.compile("<command\\s+([^>/]*?)(?:/?>)(?:(.*?)</command>)?", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PROP = Pattern.compile("<property\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"\\s*/?>", Pattern.CASE_INSENSITIVE);

    public ParsedChatOutput parse(String output) {
        String text = output == null ? "" : output;
        String feedback = clean(extractFirst(FEEDBACK, text));
        String library = clean(extractFirst(SYSML_LIBRARY, text));
        if (!library.isBlank()) {
            return new ParsedChatOutput(TYPE_SYSML_LIBRARY, feedback, library, List.of());
        }
        String model = clean(extractFirst(SYSML_MODEL, text));
        if (!model.isBlank()) {
            return new ParsedChatOutput(TYPE_SYSML_MODEL, feedback, model, List.of());
        }
        String commandBlock = extractFirst(COMMANDS, text);
        List<ChangeOperation> changes = parseCommands(commandBlock);
        if (!changes.isEmpty()) {
            return new ParsedChatOutput(TYPE_SIRIUS_COMMANDS, feedback, null, changes);
        }
        return new ParsedChatOutput(TYPE_FEEDBACK_ONLY, feedback.isBlank() ? stripTags(text).trim() : feedback, null, List.of());
    }

    private List<ChangeOperation> parseCommands(String commandBlock) {
        List<ChangeOperation> changes = new ArrayList<>();
        if (commandBlock == null || commandBlock.isBlank()) {
            return changes;
        }
        Matcher matcher = COMMAND.matcher(commandBlock);
        while (matcher.find()) {
            Map<String, String> attrs = parseAttributes(matcher.group(1));
            String nested = matcher.group(2) == null ? "" : matcher.group(2);
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            Matcher propMatcher = PROP.matcher(nested);
            while (propMatcher.find()) {
                properties.put(propMatcher.group(1), propMatcher.group(2));
            }
            String operation = firstNonBlank(attrs.get("operation"), attrs.get("action"), "CREATE").toUpperCase(java.util.Locale.ROOT);
            String elementType = firstNonBlank(attrs.get("elementType"), attrs.get("type"), "part_def");
            String name = attrs.get("name");
            UUID parentId = parseUuid(attrs.get("parentId"));
            UUID targetId = parseUuid(attrs.get("targetId"));
            UUID sourceId = parseUuid(attrs.get("sourceId"));
            UUID targetId2 = parseUuid(attrs.get("targetId2"));
            String relationshipType = attrs.get("relationshipType");
            changes.add(new ChangeOperation(operation, parentId, elementType, name, targetId, properties, sourceId, targetId2, relationshipType));
        }
        return changes;
    }

    private Map<String, String> parseAttributes(String raw) {
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            attrs.put(matcher.group(1), decode(matcher.group(2)));
        }
        return attrs;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String clean(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("<![CDATA[") && text.endsWith("]]>") && text.length() >= 12) {
            text = text.substring(9, text.length() - 3).trim();
        }
        return decode(text);
    }

    private String stripTags(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ");
    }

    private String decode(String value) {
        return value == null ? "" : value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    public record ParsedChatOutput(String type, String feedback, String sysmlText, List<ChangeOperation> changes) {
    }
}
