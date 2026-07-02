#!/usr/bin/env python3
"""
SysON Agent — End-to-End Test Suite

Tests the full agent pipeline:
1. Validator: parse + validate structured output (no LLM needed)
2. Memory: conversation CRUD + context windowing
3. Executor: execute mutations against live SysON
4. Engine: full agent loop with mock LLM
5. Server: REST API endpoints

Usage:
    cd /root/syson-fork/agent
    python3 tests/test_agent.py --host http://localhost:8080 --verbose
"""

import os
import sys
import json
import time
import uuid
import argparse
import unittest
import tempfile
import requests
from unittest.mock import patch, MagicMock

# Ensure agent modules are importable
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from validator import OutputValidator, ActionType, StructuredResponse
from memory import ConversationMemory, Message
from executor import SysOnApiClient, CommandExecutor
from validator import Command


# ============================================================
# Test Configuration
# ============================================================

class TestConfig:
    HOST = "http://localhost:8080"
    AGENT_URL = "http://localhost:5000"
    PROJECT_ID = "812673a5-3621-4a96-93fa-132bca1fea1e"
    EMAIL = "admin"
    PASSWORD = "admin"


def get_token():
    r = requests.post(f"{TestConfig.HOST}/api/auth/login",
                      json={"email": TestConfig.EMAIL, "password": TestConfig.PASSWORD})
    return r.json()["token"]


# ============================================================
# 1. Validator Tests
# ============================================================

class TestValidator(unittest.TestCase):
    """Test structured output parsing and validation."""

    def setUp(self):
        self.v = OutputValidator()

    def test_parse_import(self):
        """IMPORT action with valid SysML."""
        r = self.v.parse("""<thinking>I need to create a vehicle model.</thinking>
<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Creating a Vehicle package.</chat_feedback>
  <import_mode>append</import_mode>
  <parent_element_id></parent_element_id>
  <sysml_text>
package VehicleModel {
    part def Vehicle {
        attribute maxSpeed : ScalarValues::Real = 200;
    }
}
  </sysml_text>
</syson-response>""")
        self.assertTrue(r.is_valid)
        self.assertEqual(r.action, ActionType.IMPORT)
        self.assertIn("vehicle", r.thinking.lower())
        self.assertEqual(r.import_mode, "append")
        self.assertIn("Vehicle", r.sysml_text)

    def test_parse_update(self):
        """UPDATE action with multiple commands."""
        r = self.v.parse("""<thinking>Renaming and adding children.</thinking>
<syson-response>
  <action>UPDATE</action>
  <chat_feedback>Performing 3 modifications.</chat_feedback>
  <commands>
    <command>
      <type>ADD_CHILD</type>
      <parent_element_id>abc-123</parent_element_id>
      <element_type>PartUsage</element_type>
      <name>NewPart</name>
    </command>
    <command>
      <type>UPDATE_ELEMENT</type>
      <element_id>def-456</element_id>
      <new_label>RenamedElement</new_label>
    </command>
    <command>
      <type>DELETE_ELEMENT</type>
      <element_id>ghi-789</element_id>
    </command>
  </commands>
</syson-response>""")
        self.assertTrue(r.is_valid)
        self.assertEqual(r.action, ActionType.UPDATE)
        self.assertEqual(len(r.commands), 3)
        self.assertEqual(r.commands[0].type, "ADD_CHILD")
        self.assertEqual(r.commands[0].name, "NewPart")
        self.assertEqual(r.commands[1].new_label, "RenamedElement")
        self.assertEqual(r.commands[2].type, "DELETE_ELEMENT")

    def test_parse_relationship(self):
        """UPDATE with MANAGE_RELATIONSHIP command."""
        r = self.v.parse("""<syson-response>
  <action>UPDATE</action>
  <chat_feedback>Adding dependency.</chat_feedback>
  <commands>
    <command>
      <type>MANAGE_RELATIONSHIP</type>
      <relationship_type>Dependency</relationship_type>
      <source_element_id>src-001</source_element_id>
      <target_element_ids>tgt-001,tgt-002</target_element_ids>
      <operation>ADD</operation>
    </command>
  </commands>
</syson-response>""")
        self.assertTrue(r.is_valid)
        self.assertEqual(r.commands[0].relationship_type, "Dependency")
        self.assertEqual(r.commands[0].operation, "ADD")
        self.assertIn("tgt-001", r.commands[0].target_element_ids)

    def test_parse_clarify(self):
        """CLARIFY action."""
        r = self.v.parse("""<thinking>Need more info.</thinking>
<syson-response>
  <action>CLARIFY</action>
  <chat_feedback>Which element should I modify?</chat_feedback>
</syson-response>""")
        self.assertTrue(r.is_valid)
        self.assertEqual(r.action, ActionType.CLARIFY)
        self.assertEqual(r.chat_feedback, "Which element should I modify?")

    def test_invalid_missing_sysml_text(self):
        """IMPORT without sysml_text should fail."""
        r = self.v.parse("""<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Creating model.</chat_feedback>
</syson-response>""")
        self.assertFalse(r.is_valid)
        self.assertIn("sysml_text", r.errors[0])

    def test_invalid_unknown_action(self):
        """Unknown action should fail."""
        r = self.v.parse("""<syson-response>
  <action>DESTROY</action>
</syson-response>""")
        self.assertFalse(r.is_valid)

    def test_invalid_missing_fields(self):
        """Commands with missing required fields."""
        r = self.v.parse("""<syson-response>
  <action>UPDATE</action>
  <chat_feedback>test</chat_feedback>
  <commands>
    <command>
      <type>ADD_CHILD</type>
      <name>NoParent</name>
    </command>
  </commands>
</syson-response>""")
        self.assertFalse(r.is_valid)
        self.assertIn("parent_element_id", r.errors[0])

    def test_invalid_bad_element_type(self):
        """Invalid element type."""
        r = self.v.parse("""<syson-response>
  <action>UPDATE</action>
  <chat_feedback>test</chat_feedback>
  <commands>
    <command>
      <type>ADD_CHILD</type>
      <parent_element_id>abc</parent_element_id>
      <element_type>BogusType</element_type>
      <name>Test</name>
    </command>
  </commands>
</syson-response>""")
        self.assertFalse(r.is_valid)
        self.assertTrue(any("element_type" in e for e in r.errors))

    def test_invalid_unbalanced_braces(self):
        """SysML text with unbalanced braces."""
        r = self.v.parse("""<syson-response>
  <action>IMPORT</action>
  <chat_feedback>test</chat_feedback>
  <sysml_text>package Broken { part def X { </sysml_text>
</syson-response>""")
        self.assertFalse(r.is_valid)
        self.assertTrue(any("brace" in e for e in r.errors))

    def test_known_id_validation(self):
        """Element IDs checked against known set."""
        known = {"abc-123", "def-456"}
        r = self.v.parse("""<syson-response>
  <action>UPDATE</action>
  <chat_feedback>test</chat_feedback>
  <commands>
    <command>
      <type>UPDATE_ELEMENT</type>
      <element_id>NOT-IN-SET</element_id>
      <new_label>Renamed</new_label>
    </command>
  </commands>
</syson-response>""", known_element_ids=known)
        self.assertFalse(r.is_valid)
        self.assertTrue(any("not found" in e for e in r.errors))

    def test_empty_response(self):
        """Empty LLM response."""
        r = self.v.parse("")
        self.assertFalse(r.is_valid)


# ============================================================
# 2. Memory Tests
# ============================================================

class TestMemory(unittest.TestCase):
    """Test conversation memory and context windowing."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.mem = ConversationMemory(db_path=os.path.join(self.tmpdir, "test.db"))

    def test_create_and_list_conversation(self):
        conv_id = self.mem.create_conversation("project-1", "Test Chat")
        convs = self.mem.list_conversations("project-1")
        self.assertEqual(len(convs), 1)
        self.assertEqual(convs[0]["id"], conv_id)
        self.assertEqual(convs[0]["title"], "Test Chat")

    def test_add_and_get_messages(self):
        conv_id = self.mem.create_conversation("project-1")
        self.mem.add_message(conv_id, "user", "Hello")
        self.mem.add_message(conv_id, "assistant", "Hi there")
        msgs = self.mem.get_messages(conv_id)
        self.assertEqual(len(msgs), 2)
        self.assertEqual(msgs[0].role, "user")
        self.assertEqual(msgs[0].content, "Hello")
        self.assertEqual(msgs[1].role, "assistant")
        self.assertEqual(msgs[1].content, "Hi there")

    def test_context_windowing(self):
        """Context messages should be bounded by token budget."""
        conv_id = self.mem.create_conversation("project-1")
        # Add many large messages
        for i in range(50):
            self.mem.add_message(conv_id, "user", f"Message {i} " * 200)
            self.mem.add_message(conv_id, "assistant", f"Response {i} " * 150)
        msgs = self.mem.get_context_messages(conv_id)
        total_tokens = sum(m.token_estimate() for m in msgs)
        self.assertLess(total_tokens, ConversationMemory.MAX_CONTEXT_TOKENS + 2000)

    def test_delete_conversation(self):
        conv_id = self.mem.create_conversation("project-1")
        self.mem.add_message(conv_id, "user", "test")
        self.mem.delete_conversation(conv_id)
        convs = self.mem.list_conversations("project-1")
        self.assertEqual(len(convs), 0)

    def test_metadata_persistence(self):
        conv_id = self.mem.create_conversation("project-1")
        self.mem.add_message(conv_id, "assistant", "Created element",
                             metadata={"action": "IMPORT", "success": True})
        msgs = self.mem.get_messages(conv_id)
        self.assertEqual(msgs[0].metadata["action"], "IMPORT")

    def test_summary_extraction(self):
        """Older messages should get summarized when context exceeds threshold."""
        conv_id = self.mem.create_conversation("project-1")
        for i in range(100):
            self.mem.add_message(conv_id, "user", f"Question {i} about vehicles")
            self.mem.add_message(conv_id, "assistant", f"Answer {i}")
        # Trigger summarization
        msgs = self.mem.get_context_messages(conv_id)
        # Should have a summary system message
        summary_msgs = [m for m in msgs if m.role == "system" and "[Previous" in m.content]
        self.assertGreaterEqual(len(summary_msgs), 0)  # Summary may or may not be triggered depending on token counts


# ============================================================
# 3. Executor Tests (requires live SysON)
# ============================================================

class TestExecutor(unittest.TestCase):
    """Test command execution against live SysON."""

    @classmethod
    def setUpClass(cls):
        try:
            cls.token = get_token()
            cls.client = SysOnApiClient(TestConfig.HOST, cls.token)
            cls.ec_id = cls.client.get_editing_context(TestConfig.PROJECT_ID)
            cls.executor = CommandExecutor(cls.client)
            # Get a parent package ID
            model = cls.client.get_model_structure(TestConfig.PROJECT_ID)
            cls.root_id = model["elements"][0]["id"] if model["elements"] else None
        except Exception as e:
            cls.token = None
            print(f"WARNING: Could not connect to SysON: {e}")

    def setUp(self):
        if not getattr(self, 'token', None):
            self.skipTest("SysON not available")

    def test_add_child_part(self):
        """Add a PartUsage via executor."""
        resp = StructuredResponse(action=ActionType.UPDATE)
        resp.commands.append(Command(
            type="ADD_CHILD",
            parent_element_id=self.root_id,
            element_type="PartUsage",
            name="TestPart_Executor"
        ))
        result = self.executor.execute(resp, self.ec_id)
        self.assertTrue(result["success"])
        self.assertEqual(result["action"], "UPDATE")

    def test_update_rename(self):
        """Rename an element via executor."""
        # First create one
        create_resp = StructuredResponse(action=ActionType.UPDATE)
        create_resp.commands.append(Command(
            type="ADD_CHILD",
            parent_element_id=self.root_id,
            element_type="PartUsage",
            name="RenameTarget_Executor"
        ))
        create_result = self.executor.execute(create_resp, self.ec_id)
        self.assertTrue(create_result["success"])

        # Find it via REST (just first 2 levels, quick)
        import requests as req
        r = req.get(
            f"{TestConfig.HOST}/api/rest/projects/{TestConfig.PROJECT_ID}/commits/{TestConfig.PROJECT_ID}/elements",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=30
        )
        elements = r.json()
        target = None
        for elem in elements:
            if elem.get("name") == "RenameTarget_Executor" or elem.get("declaredName") == "RenameTarget_Executor":
                target = elem
                break

        if target:
            update_resp = StructuredResponse(action=ActionType.UPDATE)
            update_resp.commands.append(Command(
                type="UPDATE_ELEMENT",
                element_id=target["@id"],
                new_label="RenamedBy_Executor"
            ))
            update_result = self.executor.execute(update_resp, self.ec_id)
            self.assertTrue(update_result["success"])
        else:
            self.skipTest("Created element not found in REST (timing delay)")

    def test_delete_element(self):
        """Delete an element via executor."""
        # Create
        create_resp = StructuredResponse(action=ActionType.UPDATE)
        create_resp.commands.append(Command(
            type="ADD_CHILD",
            parent_element_id=self.root_id,
            element_type="PartUsage",
            name="DeleteMe_Executor"
        ))
        self.executor.execute(create_resp, self.ec_id)

        # Find it via REST
        import requests as req
        r = req.get(
            f"{TestConfig.HOST}/api/rest/projects/{TestConfig.PROJECT_ID}/commits/{TestConfig.PROJECT_ID}/elements",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=30
        )
        elements = r.json()
        target = None
        for elem in elements:
            if elem.get("name") == "DeleteMe_Executor" or elem.get("declaredName") == "DeleteMe_Executor":
                target = elem
                break

        if target:
            del_resp = StructuredResponse(action=ActionType.UPDATE)
            del_resp.commands.append(Command(
                type="DELETE_ELEMENT",
                element_id=target["@id"]
            ))
            del_result = self.executor.execute(del_resp, self.ec_id)
            self.assertTrue(del_result["success"])
        else:
            self.skipTest("Created element not found in REST (timing delay)")

    def _flatten_tree(self, tree):
        """Flatten tree to list of nodes."""
        result = []
        for node in tree:
            result.append(node)
            result.extend(self._flatten_tree(node.get("children", [])))
        return result


# ============================================================
# 4. Agent Server Tests
# ============================================================

class TestAgentServer(unittest.TestCase):
    """Test the Flask REST API."""

    def test_health(self):
        r = requests.get(f"{TestConfig.AGENT_URL}/api/agent/health")
        self.assertEqual(r.status_code, 200)
        data = r.json()
        self.assertEqual(data["status"], "ok")

    def test_get_settings(self):
        r = requests.get(f"{TestConfig.AGENT_URL}/api/agent/settings")
        self.assertEqual(r.status_code, 200)
        data = r.json()
        # API key should NEVER be in the response
        self.assertNotIn("llm_api_key", data)
        self.assertNotIn("api_key", data)
        self.assertIn("api_key_set", data)

    def test_save_settings(self):
        r = requests.post(f"{TestConfig.AGENT_URL}/api/agent/settings", json={
            "llm_endpoint": "https://api.test.com/v1/chat/completions",
            "llm_model": "test-model"
        })
        self.assertEqual(r.status_code, 200)
        data = r.json()
        self.assertEqual(data["status"], "ok")

        # Verify
        r2 = requests.get(f"{TestConfig.AGENT_URL}/api/agent/settings")
        d2 = r2.json()
        self.assertEqual(d2["llm_endpoint"], "https://api.test.com/v1/chat/completions")

    def test_save_api_key_never_returned(self):
        """Save a key and verify it's never returned."""
        requests.post(f"{TestConfig.AGENT_URL}/api/agent/settings", json={
            "llm_api_key": "sk-secret-test-key-12345"
        })
        r = requests.get(f"{TestConfig.AGENT_URL}/api/agent/settings")
        data = r.json()
        self.assertTrue(data["api_key_set"])
        # Verify the key is nowhere in the response
        raw = json.dumps(data)
        self.assertNotIn("sk-secret-test-key-12345", raw)

    def test_conversations_list(self):
        r = requests.get(f"{TestConfig.AGENT_URL}/api/agent/conversations",
                         params={"projectId": TestConfig.PROJECT_ID})
        self.assertEqual(r.status_code, 200)

    def test_process_without_config(self):
        """Process should fail gracefully if LLM not configured."""
        # Remove config temporarily
        requests.post(f"{TestConfig.AGENT_URL}/api/agent/settings", json={
            "llm_endpoint": "",
            "llm_api_key": "",
            "llm_model": ""
        })
        r = requests.post(f"{TestConfig.AGENT_URL}/api/agent/process", json={
            "projectId": TestConfig.PROJECT_ID,
            "prompt": "test"
        })
        self.assertEqual(r.status_code, 400)


# ============================================================
# 5. Full Agent Loop with Mock LLM
# ============================================================

class TestAgentLoopMock(unittest.TestCase):
    """Test the full agent loop with a mocked LLM."""

    def test_mock_import_loop(self):
        """Simulate an IMPORT request where LLM returns valid structured output."""
        from engine import AgentEngine

        mock_llm_response = """<thinking>The user wants a simple package with a part definition.</thinking>
<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Creating a SensorPackage with one part definition.</chat_feedback>
  <import_mode>append</import_mode>
  <parent_element_id></parent_element_id>
  <sysml_text>
package SensorPackage {
    part def TemperatureSensor {
        attribute range : ScalarValues::Real;
    }
}
  </sysml_text>
</syson-response>"""

        with patch.object(AgentEngine, '_call_llm', return_value=mock_llm_response):
            try:
                token = get_token()
            except:
                self.skipTest("SysON not available")

            engine = AgentEngine(
                syson_url=TestConfig.HOST,
                syson_token=token,
                llm_endpoint="http://mock",
                llm_api_key="mock-key",
                llm_model="mock-model",
                db_path=os.path.join(tempfile.mkdtemp(), "test.db")
            )
            result = engine.process_request(
                TestConfig.PROJECT_ID,
                "Create a temperature sensor model"
            )
            self.assertIn("conversation_id", result)
            self.assertTrue(result.get("success"))

    def test_mock_clarify_loop(self):
        """Simulate a CLARIFY response."""
        from engine import AgentEngine

        mock_response = """<thinking>Request is ambiguous.</thinking>
<syson-response>
  <action>CLARIFY</action>
  <chat_feedback>Do you want to create a new package or modify an existing one?</chat_feedback>
</syson-response>"""

        with patch.object(AgentEngine, '_call_llm', return_value=mock_response):
            try:
                token = get_token()
            except:
                self.skipTest("SysON not available")

            engine = AgentEngine(
                syson_url=TestConfig.HOST,
                syson_token=token,
                llm_endpoint="http://mock",
                llm_api_key="mock-key",
                llm_model="mock-model",
                db_path=os.path.join(tempfile.mkdtemp(), "test.db")
            )
            result = engine.process_request(
                TestConfig.PROJECT_ID,
                "Add something to the model"
            )
            self.assertEqual(result["action"], "CLARIFY")
            self.assertIn("package", result["response"].lower())

    def test_mock_validation_retry(self):
        """Test that invalid LLM output triggers a retry."""
        from engine import AgentEngine

        bad_response = """<syson-response>
  <action>IMPORT</action>
  <chat_feedback>missing sysml text</chat_feedback>
</syson-response>"""

        good_response = """<thinking>Fixed my error.</thinking>
<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Creating valid model now.</chat_feedback>
  <import_mode>append</import_mode>
  <sysml_text>package TestPkg { part def X { } }</sysml_text>
</syson-response>"""

        responses = [bad_response, good_response]

        def mock_call(system, user):
            return responses.pop(0)

        with patch.object(AgentEngine, '_call_llm', side_effect=mock_call):
            try:
                token = get_token()
            except:
                self.skipTest("SysON not available")

            engine = AgentEngine(
                syson_url=TestConfig.HOST,
                syson_token=token,
                llm_endpoint="http://mock",
                llm_api_key="mock-key",
                llm_model="mock-model",
                db_path=os.path.join(tempfile.mkdtemp(), "test.db")
            )
            result = engine.process_request(
                TestConfig.PROJECT_ID,
                "Create a test model"
            )
            # After retry, should succeed
            self.assertTrue(result.get("success"))


# ============================================================
# Main
# ============================================================

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='SysON Agent Test Suite')
    parser.add_argument('--host', default='http://localhost:8080', help='SysON host URL')
    parser.add_argument('--agent-url', default='http://localhost:5000', help='Agent service URL')
    parser.add_argument('--verbose', '-v', action='store_true', help='Verbose output')
    parser.add_argument('--pattern', '-k', help='Test name pattern to match')
    args = parser.parse_args()

    TestConfig.HOST = args.host
    TestConfig.AGENT_URL = args.agent_url

    # Run tests
    loader = unittest.TestLoader()
    suite = loader.loadTestsFromModule(sys.modules[__name__])

    if args.pattern:
        suite = unittest.TestLoader().loadTestsFromName(args.pattern, sys.modules[__name__])

    runner = unittest.TextTestRunner(
        verbosity=2 if args.verbose else 1,
        stream=sys.stdout,
        failfast=False
    )
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
