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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the XML wrapper contract used between the LLM and the chat UI.
 */
class ChatStructuredOutputParserTest {

    private final ChatStructuredOutputParser parser = new ChatStructuredOutputParser();

    @Test
    void parse_sysmlModelWrapper_shouldExtractFeedbackAndSource() {
        String output = "<syson-response>"
                + "<chat_feedback verbosity=\"done\">Created a cooling fan model.</chat_feedback>"
                + "<sysml_model name=\"CoolingFan\"><![CDATA[package CoolingFan { part def FanAssembly { } }]]></sysml_model>"
                + "</syson-response>";

        ChatStructuredOutputParser.ParsedChatOutput parsed = parser.parse(output);

        assertEquals(ChatStructuredOutputParser.TYPE_SYSML_MODEL, parsed.type());
        assertEquals("Created a cooling fan model.", parsed.feedback());
        assertTrue(parsed.sysmlText().contains("package CoolingFan"));
        assertTrue(parsed.changes().isEmpty());
    }

    @Test
    void parse_sysmlLibraryWrapper_shouldExtractLibrarySource() {
        String output = "<syson-response>"
                + "<chat_feedback verbosity=\"done\">Prepared library definitions.</chat_feedback>"
                + "<sysml_library name=\"CoolingFanLibrary\"><![CDATA[package CoolingFanLibrary { part def Blade { } }]]></sysml_library>"
                + "</syson-response>";

        ChatStructuredOutputParser.ParsedChatOutput parsed = parser.parse(output);

        assertEquals(ChatStructuredOutputParser.TYPE_SYSML_LIBRARY, parsed.type());
        assertTrue(parsed.sysmlText().contains("part def Blade"));
    }

    @Test
    void parse_siriusCommandsWrapper_shouldExtractMultipleCommands() {
        String output = "<syson-response>"
                + "<chat_feedback verbosity=\"progress\">Prepared Sirius commands for review.</chat_feedback>"
                + "<sirius_commands target=\"existing-model\">"
                + "<command action=\"CREATE\" elementType=\"part_def\" name=\"FanAssembly\"><property name=\"description\" value=\"Cooling fan assembly\"/></command>"
                + "<command action=\"CREATE\" elementType=\"port\" name=\"airIn\"/>"
                + "</sirius_commands>"
                + "</syson-response>";

        ChatStructuredOutputParser.ParsedChatOutput parsed = parser.parse(output);

        assertEquals(ChatStructuredOutputParser.TYPE_SIRIUS_COMMANDS, parsed.type());
        assertEquals(2, parsed.changes().size());
        assertEquals("FanAssembly", parsed.changes().get(0).name());
        assertEquals("Cooling fan assembly", parsed.changes().get(0).properties().get("description"));
    }

    @Test
    void parse_feedbackOnly_shouldRemainDisplayableInChat() {
        ChatStructuredOutputParser.ParsedChatOutput parsed = parser.parse(
                "<syson-response><chat_feedback verbosity=\"error\">I need more detail.</chat_feedback></syson-response>");

        assertEquals(ChatStructuredOutputParser.TYPE_FEEDBACK_ONLY, parsed.type());
        assertEquals("I need more detail.", parsed.feedback());
        assertNotNull(parsed.changes());
    }
}
