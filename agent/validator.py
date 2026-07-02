"""
SysON Agent — Structured Output Validator

Parses and validates LLM responses against the expected XML schema.
Supports: IMPORT, UPDATE, CLARIFY, and THINKING blocks.
"""

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Optional
from enum import Enum


class ActionType(Enum):
    IMPORT = "IMPORT"
    UPDATE = "UPDATE"
    CLARIFY = "CLARIFY"
    THINKING = "THINKING"


@dataclass
class Command:
    """A single model manipulation command."""
    type: str = ""
    element_id: str = ""
    parent_element_id: str = ""
    element_type: str = ""
    name: str = ""
    new_label: str = ""
    new_short_name: str = ""
    new_body: str = ""
    relationship_type: str = ""
    source_element_id: str = ""
    target_element_ids: str = ""
    operation: str = ""

    def validate(self) -> list[str]:
        """Return list of validation errors (empty = valid)."""
        errors = []
        if self.type == "ADD_CHILD":
            if not self.parent_element_id:
                errors.append("ADD_CHILD requires parent_element_id")
            if not self.element_type:
                errors.append("ADD_CHILD requires element_type")
            if not self.name:
                errors.append("ADD_CHILD requires name")
        elif self.type == "UPDATE_ELEMENT":
            if not self.element_id:
                errors.append("UPDATE_ELEMENT requires element_id")
            if not any([self.new_label, self.new_short_name, self.new_body]):
                errors.append("UPDATE_ELEMENT requires at least one of: new_label, new_short_name, new_body")
        elif self.type == "DELETE_ELEMENT":
            if not self.element_id:
                errors.append("DELETE_ELEMENT requires element_id")
        elif self.type == "MANAGE_RELATIONSHIP":
            if not self.relationship_type:
                errors.append("MANAGE_RELATIONSHIP requires relationship_type")
            if not self.source_element_id:
                errors.append("MANAGE_RELATIONSHIP requires source_element_id")
            if not self.target_element_ids:
                errors.append("MANAGE_RELATIONSHIP requires target_element_ids")
            if self.operation not in ("ADD", "REMOVE"):
                errors.append(f"MANAGE_RELATIONSHIP operation must be ADD or REMOVE, got '{self.operation}'")
        else:
            errors.append(f"Unknown command type: '{self.type}'")
        return errors


@dataclass
class StructuredResponse:
    """Parsed LLM response."""
    action: ActionType = ActionType.THINKING
    thinking: str = ""
    chat_feedback: str = ""
    import_mode: str = "append"
    parent_element_id: str = ""
    sysml_text: str = ""
    commands: list[Command] = field(default_factory=list)
    raw_response: str = ""
    errors: list[str] = field(default_factory=list)

    @property
    def is_valid(self) -> bool:
        return len(self.errors) == 0


VALID_ELEMENT_TYPES = {
    "PartUsage", "Package", "AttributeUsage", "ItemUsage", "PortUsage",
    "ActionUsage", "StateUsage", "RequirementUsage", "ConstraintUsage",
    "ConnectionUsage", "FlowConnectionUsage", "InterfaceUsage",
    "PartDefinition", "ItemDefinition", "AttributeDefinition",
    "PortDefinition", "ActionDefinition", "StateDefinition",
    "RequirementDefinition", "ConstraintDefinition",
    "InterfaceDefinition", "EnumerationDefinition",
}

VALID_RELATIONSHIP_TYPES = {"Dependency", "Subclassification", "Specialization"}


class OutputValidator:
    """Parses and validates LLM structured output."""

    def parse(self, raw_response: str, known_element_ids: Optional[set[str]] = None) -> StructuredResponse:
        """
        Parse LLM response into a StructuredResponse.
        
        Args:
            raw_response: The raw LLM text response
            known_element_ids: Set of valid element @id values in the current model.
                               If provided, element_id references are checked against this set.
        
        Returns:
            StructuredResponse with parsed data and any validation errors.
        """
        result = StructuredResponse(raw_response=raw_response)
        known = known_element_ids or set()

        # Extract thinking block
        thinking_match = re.search(r"<thinking>(.*?)</thinking>", raw_response, re.DOTALL)
        if thinking_match:
            result.thinking = thinking_match.group(1).strip()
        else:
            # Check for thinking as plain text before syson-response
            sr_match = re.search(r"<syson-response>", raw_response, re.DOTALL)
            if sr_match:
                pre = raw_response[:sr_match.start()].strip()
                if pre:
                    result.thinking = pre

        # Extract syson-response block
        sr_match = re.search(r"<syson-response>(.*?)</syson-response>", raw_response, re.DOTALL)
        if not sr_match:
            # No structured response found — treat as plain text/clarification
            result.action = ActionType.CLARIFY
            result.chat_feedback = raw_response.strip()
            if not result.chat_feedback:
                result.errors.append("Empty response from LLM")
            return result

        sr_content = sr_match.group(1)

        # Parse XML
        try:
            # Wrap in root tag for XML parsing
            root = ET.fromstring(f"<root>{sr_content}</root>")
        except ET.ParseError as e:
            result.errors.append(f"XML parse error: {e}")
            return result

        # Extract action
        action_elem = root.find("action")
        if action_elem is None or not action_elem.text:
            result.errors.append("Missing <action> element")
            return result

        action_str = action_elem.text.strip().upper()
        try:
            result.action = ActionType(action_str)
        except ValueError:
            result.errors.append(f"Unknown action: '{action_str}'. Must be IMPORT, UPDATE, or CLARIFY")
            return result

        # Extract chat_feedback
        feedback_elem = root.find("chat_feedback")
        result.chat_feedback = feedback_elem.text.strip() if feedback_elem is not None and feedback_elem.text else ""

        # Parse based on action type
        if result.action == ActionType.IMPORT:
            self._parse_import(root, result)
        elif result.action == ActionType.UPDATE:
            self._parse_update(root, result, known)
        elif result.action == ActionType.CLARIFY:
            pass  # Just chat_feedback

        # Validate commands
        for i, cmd in enumerate(result.commands):
            cmd_errors = cmd.validate()
            for e in cmd_errors:
                result.errors.append(f"Command {i+1}: {e}")

        return result

    def _parse_import(self, root: ET.Element, result: StructuredResponse):
        """Parse IMPORT action."""
        mode_elem = root.find("import_mode")
        if mode_elem is not None and mode_elem.text:
            mode = mode_elem.text.strip().lower()
            if mode not in ("append", "replace", "library"):
                result.errors.append(f"Invalid import_mode: '{mode}'. Must be append, replace, or library")
            else:
                result.import_mode = mode

        parent_elem = root.find("parent_element_id")
        if parent_elem is not None and parent_elem.text:
            result.parent_element_id = parent_elem.text.strip()

        sysml_elem = root.find("sysml_text")
        if sysml_elem is None or not sysml_elem.text:
            result.errors.append("IMPORT requires <sysml_text> element")
        else:
            result.sysml_text = sysml_elem.text.strip()
            # Basic SysML syntax validation
            syntax_errors = self._validate_sysml_syntax(result.sysml_text)
            result.errors.extend(syntax_errors)

    def _parse_update(self, root: ET.Element, result: StructuredResponse, known_ids: set[str]):
        """Parse UPDATE action."""
        commands_elem = root.find("commands")
        if commands_elem is None:
            result.errors.append("UPDATE requires <commands> element")
            return

        for cmd_elem in commands_elem.findall("command"):
            cmd = Command()

            type_elem = cmd_elem.find("type")
            cmd.type = type_elem.text.strip() if type_elem is not None and type_elem.text else ""

            for child in cmd_elem:
                tag = child.tag
                text = (child.text or "").strip()
                if tag == "type":
                    pass  # Already handled
                elif tag == "element_id":
                    cmd.element_id = text
                elif tag == "parent_element_id":
                    cmd.parent_element_id = text
                elif tag == "element_type":
                    cmd.element_type = text
                    if cmd.element_type not in VALID_ELEMENT_TYPES:
                        result.errors.append(
                            f"Invalid element_type: '{cmd.element_type}'. Valid: {', '.join(sorted(VALID_ELEMENT_TYPES))}"
                        )
                elif tag == "name":
                    cmd.name = text
                elif tag == "new_label":
                    cmd.new_label = text
                elif tag == "new_short_name":
                    cmd.new_short_name = text
                elif tag == "new_body":
                    cmd.new_body = text
                elif tag == "relationship_type":
                    cmd.relationship_type = text
                    if cmd.relationship_type not in VALID_RELATIONSHIP_TYPES:
                        result.errors.append(
                            f"Invalid relationship_type: '{cmd.relationship_type}'. Valid: {', '.join(sorted(VALID_RELATIONSHIP_TYPES))}"
                        )
                elif tag == "source_element_id":
                    cmd.source_element_id = text
                elif tag == "target_element_ids":
                    cmd.target_element_ids = text
                elif tag == "operation":
                    cmd.operation = text

            # Check element IDs against known IDs if provided
            if known_ids:
                for id_attr in [cmd.element_id, cmd.parent_element_id, cmd.source_element_id]:
                    if id_attr and id_attr not in known_ids:
                        result.errors.append(f"Element ID '{id_attr[:12]}...' not found in current model")

            # Parse comma-separated target IDs
            if cmd.target_element_ids:
                targets = [t.strip() for t in cmd.target_element_ids.split(",") if t.strip()]
                if known_ids:
                    for t in targets:
                        if t not in known_ids:
                            result.errors.append(f"Target element ID '{t[:12]}...' not found in current model")

            result.commands.append(cmd)

    def _validate_sysml_syntax(self, text: str) -> list[str]:
        """Basic SysML syntax validation (not full parser, just common errors)."""
        errors = []
        if not text.strip():
            errors.append("SysML text is empty")
            return errors

        # Check brace balance
        open_braces = text.count("{")
        close_braces = text.count("}")
        if open_braces != close_braces:
            errors.append(f"Unbalanced braces: {open_braces} opening vs {close_braces} closing")

        # Check for at least one keyword
        keywords = ["package", "part def", "part ", "item def", "item ", "attribute",
                    "port", "connection", "flow", "requirement", "constraint", "action",
                    "state", "interface", "import", "enum def"]
        has_keyword = any(kw in text for kw in keywords)
        if not has_keyword:
            errors.append("No SysML keyword found in text")

        return errors


# ============================================================
# Quick test
# ============================================================

if __name__ == "__main__":
    v = OutputValidator()

    # Test IMPORT
    r1 = v.parse("""<thinking>I need to create a simple vehicle model.</thinking>
<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Creating a Vehicle package with a part definition.</chat_feedback>
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
    print(f"Test IMPORT: action={r1.action}, valid={r1.is_valid}, errors={r1.errors}")

    # Test UPDATE
    r2 = v.parse("""<thinking>Renaming the element.</thinking>
<syson-response>
  <action>UPDATE</action>
  <chat_feedback>Renaming the package.</chat_feedback>
  <commands>
    <command>
      <type>UPDATE_ELEMENT</type>
      <element_id>abc-123-def</element_id>
      <new_label>RenamedPackage</new_label>
    </command>
  </commands>
</syson-response>""")
    print(f"Test UPDATE: action={r2.action}, valid={r2.is_valid}, cmds={len(r2.commands)}, errors={r2.errors}")

    # Test CLARIFY
    r3 = v.parse("""<thinking>Need more info.</thinking>
<syson-response>
  <action>CLARIFY</action>
  <chat_feedback>Which element should I modify?</chat_feedback>
</syson-response>""")
    print(f"Test CLARIFY: action={r3.action}, valid={r3.is_valid}, feedback={r3.chat_feedback}")

    # Test invalid (missing sysml_text)
    r4 = v.parse("""<syson-response><action>IMPORT</action><chat_feedback>test</chat_feedback></syson-response>""")
    print(f"Test invalid: valid={r4.is_valid}, errors={r4.errors}")
