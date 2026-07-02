"""
SysON Agent — Core Engine

The mini-agentic loop that:
1. Builds context (model state + conversation history + system prompt)
2. Sends to LLM
3. Parses structured output
4. Validates
5. Executes (or asks for clarification)
6. Supports thinking loops (LLM can request info before acting)
"""

import json
import logging
import time
import os
from typing import Optional
from pathlib import Path

from memory import ConversationMemory
from validator import OutputValidator, StructuredResponse, ActionType
from executor import CommandExecutor, SysOnApiClient

logger = logging.getLogger(__name__)


class AgentEngine:
    """
    The core agent engine that orchestrates the LLM thinking loop.
    
    Flow:
        user_request → build_context → LLM_call → parse → validate → execute → store_response
    
    If the LLM output fails validation, it can retry with feedback (max 2 retries).
    If the LLM asks for clarification, the user sees the question and must respond.
    """

    MAX_RETRIES = 2
    MAX_THINKING_TURNS = 3

    def __init__(
        self,
        syson_url: str,
        syson_token: str,
        llm_endpoint: str,
        llm_api_key: str,
        llm_model: str,
        db_path: str = None
    ):
        self.syson_client = SysOnApiClient(syson_url, syson_token)
        self.llm_endpoint = llm_endpoint
        self.llm_api_key = llm_api_key
        self.llm_model = llm_model
        self.memory = ConversationMemory(db_path)
        self.validator = OutputValidator()
        self.executor = CommandExecutor(self.syson_client)
        
        # Load system prompt
        prompt_path = Path(__file__).parent / "prompts" / "SYSTEM_PROMPT.md"
        self.system_prompt = prompt_path.read_text() if prompt_path.exists() else ""
        
        # Load structured output spec
        spec_path = Path(__file__).parent / "prompts" / "STRUCTURED_OUTPUT_SPEC.md"
        self.output_spec = spec_path.read_text() if spec_path.exists() else ""

    def process_request(
        self,
        project_id: str,
        user_message: str,
        conversation_id: Optional[str] = None
    ) -> dict:
        """
        Process a user request through the full agent loop.
        
        Returns:
            dict with keys:
                - conversation_id: str
                - thinking: str (agent's analysis)
                - response: str (user-facing message)
                - action: str (IMPORT/UPDATE/CLARIFY/ERROR)
                - execution_result: dict (if action was executed)
                - validation_errors: list (if validation failed)
                - success: bool
        """
        # Create or get conversation
        if not conversation_id:
            title = user_message[:50] + ('...' if len(user_message) > 50 else '')
            conversation_id = self.memory.create_conversation(project_id, title)

        # Store user message
        self.memory.add_message(conversation_id, 'user', user_message)

        # Fetch model state ONCE (expensive REST call — ~20s for 919 elements)
        ec_id = None
        model = None
        model_text = ""
        known_ids = set()
        try:
            ec_id = self.syson_client.get_editing_context(project_id)
            model = self.syson_client.get_model_structure(project_id)
            model_text = self.syson_client.model_tree_to_text(model['elements'])
            known_ids = set(model['all_ids'])
        except Exception as e:
            logger.warning(f"Failed to get model state: {e}")

        # Build context
        system_prompt, history_msgs = self._build_context(project_id, conversation_id, model, model_text, ec_id)

        # Run agent loop with cached data
        result = self._agent_loop(project_id, conversation_id, user_message, system_prompt, history_msgs, ec_id, known_ids, model)

        # Store assistant response with debug info
        metadata = {
            'action': result.get('action', 'UNKNOWN'),
            'success': result.get('success', False)
        }
        self.memory.add_message(
            conversation_id, 'assistant', result.get('response', ''),
            metadata=metadata
        )

        result['conversation_id'] = conversation_id
        # Include debug info for pipeline visibility
        if '_debug_raw' in result:
            result['debug'] = {
                'raw_llm_response': result.pop('_debug_raw', '')[:5000],
                'parsed_action': result.pop('_debug_parsed_action', ''),
                'validation_errors': result.pop('_debug_validation_errors', []),
                'execution_raw': result.pop('_debug_execution', {}),
                'retries': result.pop('_debug_retries', 0),
                'truncated': result.pop('_debug_truncated', False),
            }
        return result

    def _build_context(self, project_id: str, conversation_id: str, model: dict = None, model_text: str = "", ec_id: str = None):
        """Build the system prompt (without history) and return history messages separately.

        Returns: (system_prompt: str, history_msgs: list)
        """
        parts = []

        # System prompt
        parts.append(self.system_prompt)
        parts.append("\n\n## Structured Output Specification\n")
        parts.append(self.output_spec)

        # Current model state (from cached data)
        if model and model_text:
            if len(model_text) > 16000:
                model_text = model_text[:16000] + "\n... (truncated, showing first 16000 chars)"
            parts.append(f"\n\n## Current Model State ({model['total_count']} elements)\n")
            parts.append(f"```\n{model_text}\n```")
            # Add a flat reference of all named elements for ID lookups during UPDATE
            if model.get('named_elements'):
                id_refs = []
                # Group children under parents for clearer context
                shown_ids = set()
                for name, eid, etype, parent_name in sorted(model['named_elements'], key=lambda x: (x[3] or '\uffff', x[0].lower())):
                    if eid in shown_ids:
                        continue
                    shown_ids.add(eid)
                    if parent_name:
                        id_refs.append(f"  {etype}: {name} → {eid}  (parent: {parent_name})")
                    else:
                        id_refs.append(f"  {etype}: {name} → {eid}")
                parts.append(f"\n## Element ID Reference ({len(id_refs)} named elements)\n")
                parts.append("Use these IDs for <element_id>. For requirements, use the RequirementUsage ID (NOT child attributes).\n")
                if len(id_refs) > 150:
                    id_refs = id_refs[:150]
                parts.append("```\n" + "\n".join(id_refs) + "\n```")
            if ec_id:
                parts.append(f"\nEditing Context ID: {ec_id}")
        elif ec_id:
            parts.append(f"\n\n## Current Model State\nEditing Context ID: {ec_id}")

        system_prompt = "\n".join(parts)

        # Get conversation history as separate messages for proper role labeling
        history_msgs = self.memory.get_context_messages(conversation_id)

        return system_prompt, history_msgs

    def _agent_loop(
        self,
        project_id: str,
        conversation_id: str,
        user_message: str,
        system_prompt: str,
        history_msgs: list,
        ec_id: str = None,
        known_ids: set = None,
        model: dict = None
    ) -> dict:
        """
        Run the agent loop: call LLM, parse, validate, execute.
        Supports retries on validation failure and format enforcement.
        """
        if known_ids is None:
            known_ids = set()

        last_error = None

        for attempt in range(self.MAX_RETRIES + 1):
            # Build the user prompt (current message + any retry hints)
            user_prompt = user_message
            if last_error:
                user_prompt += (
                    f"\n\n⚠️ Your previous response had validation errors:\n"
                    f"{last_error}\n\nPlease fix and resubmit."
                )

            # Call LLM with properly role-labeled history
            try:
                raw_response = self._call_llm_with_history(system_prompt, history_msgs, user_prompt)
            except Exception as e:
                logger.error(f"LLM call failed: {e}")
                return {
                    "response": f"Error calling LLM: {e}",
                    "action": "ERROR",
                    "success": False,
                    "_debug_raw": str(e),
                    "_debug_retries": attempt,
                }

            # Parse structured output
            parsed = self.validator.parse(raw_response, known_ids)

            # TRUNCATION DETECTION: If <syson-response> is present but </syson-response>
            # is missing, the LLM response was cut off by token limits. Retry with a
            # shorter, more focused prompt asking the LLM to be concise.
            has_opening = '<syson-response>' in raw_response
            has_closing = '</syson-response>' in raw_response
            if has_opening and not has_closing:
                logger.warning(f"Truncated response detected (attempt {attempt+1}) — retrying with size limit warning")
                # Ask LLM to be more concise — response was cut off
                user_prompt = (
                    f"{user_message}\n\n"
                    f"⚠️ Your previous response was TRUNCATED — the closing </syson-response> tag was cut off "
                    f"because the response exceeded the maximum allowed length. "
                    f"You MUST keep your response SHORTER. Prioritize the most important elements. "
                    f"Limit your SysML model to 10-15 elements maximum. "
                    f"Use concise names. Skip documentation strings if needed to save space. "
                    f"CRITICAL: Ensure your response ends with </syson-response>."
                )
                last_error = "Response truncated — missing closing </syson-response> tag"
                if attempt < self.MAX_RETRIES:
                    continue
                else:
                    return {
                        "thinking": raw_response.split('</thinking>')[0].replace('<thinking>', '') if '<thinking>' in raw_response else '',
                        "response": raw_response[:500],
                        "action": "ERROR",
                        "validation_errors": [f"LLM response truncated after max retries ({len(raw_response)} chars)"],
                        "success": False,
                        "_debug_raw": raw_response,
                        "_debug_truncated": True,
                        "_debug_retries": attempt,
                    }

            # FORMAT ENFORCEMENT: If LLM returned plain text without <syson-response> tags,
            # it's not REALLY a CLARIFY — the LLM just ignored the format. Retry with a reminder.
            if parsed.action == ActionType.CLARIFY and not has_opening:
                logger.warning(f"No structured output found (attempt {attempt+1}) — retrying with format reminder")
                # The LLM didn't follow the format. Remind it forcefully.
                user_prompt = (
                    f"{user_message}\n\n"
                    f"⚠️ FORMAT VIOLATION: Your previous response did not contain a <syson-response> block. "
                    f"You MUST respond with the exact XML format. Start with <thinking> and end with </syson-response>. "
                    f"Do NOT add any conversational text outside these tags. "
                    f"If the request is unclear, use CLARIFY action WITHIN the XML format. "
                    f"Example: <syson-response><action>IMPORT</action><chat_feedback>Creating...</chat_feedback><import_mode>append</import_mode><sysml_text>package Example {{ part def Foo; }}</sysml_text></syson-response>"
                )
                last_error = "Missing required <syson-response> XML block"
                if attempt < self.MAX_RETRIES:
                    continue
                else:
                    return {
                        "thinking": "",
                        "response": raw_response[:500],
                        "action": "ERROR",
                        "validation_errors": ["LLM did not return structured output after max retries"],
                        "success": False
                    }

            # Check validation
            if not parsed.is_valid:
                last_error = "; ".join(parsed.errors)
                logger.warning(f"Validation failed (attempt {attempt+1}): {last_error}")
                if attempt < self.MAX_RETRIES:
                    continue
                else:
                    return {
                        "thinking": parsed.thinking,
                        "response": parsed.chat_feedback or raw_response,
                        "action": "ERROR",
                        "validation_errors": parsed.errors,
                        "success": False,
                        "_debug_raw": raw_response,
                        "_debug_parsed_action": parsed.action.value,
                        "_debug_validation_errors": parsed.errors,
                        "_debug_retries": attempt,
                    }

            # Validation passed — execute
            if parsed.action == ActionType.CLARIFY:
                return {
                    "thinking": parsed.thinking,
                    "response": parsed.chat_feedback,
                    "action": "CLARIFY",
                    "success": True,
                    "_debug_raw": raw_response,
                    "_debug_parsed_action": "CLARIFY",
                    "_debug_retries": attempt,
                }

            # Execute the action
            try:
                exec_result = self.executor.execute(parsed, ec_id, project_id, model)
            except Exception as e:
                return {
                    "thinking": parsed.thinking,
                    "response": f"Execution failed: {e}",
                    "action": parsed.action.value,
                    "success": False,
                    "execution_error": str(e),
                    "_debug_raw": raw_response,
                    "_debug_parsed_action": parsed.action.value,
                    "_debug_execution": {"error": str(e)},
                    "_debug_retries": attempt,
                }

            # Format response
            if exec_result.get('success'):
                msg = parsed.chat_feedback
                if parsed.action == ActionType.UPDATE:
                    succeeded = sum(1 for r in exec_result.get('results', []) if r.get('success'))
                    total = len(exec_result.get('results', []))
                    msg += f"\n\n✅ Executed {succeeded}/{total} commands successfully."
                elif parsed.action == ActionType.IMPORT:
                    msg += f"\n\n✅ SysML model imported successfully."
                
                return {
                    "thinking": parsed.thinking,
                    "response": msg,
                    "action": parsed.action.value,
                    "execution_result": exec_result,
                    "success": True,
                    "_debug_raw": raw_response,
                    "_debug_parsed_action": parsed.action.value,
                    "_debug_execution": exec_result,
                    "_debug_retries": attempt,
                }
            else:
                return {
                    "thinking": parsed.thinking,
                    "response": f"❌ Execution failed: {exec_result.get('error', 'Unknown error')}",
                    "action": parsed.action.value,
                    "execution_result": exec_result,
                    "success": False,
                    "_debug_raw": raw_response,
                    "_debug_parsed_action": parsed.action.value,
                    "_debug_execution": exec_result,
                    "_debug_retries": attempt,
                }

        # Should not reach here
        return {
            "response": "Max retries exceeded.",
            "action": "ERROR",
            "success": False
        }

    def _call_llm(self, system_prompt: str, user_prompt: str) -> str:
        """Call the LLM API (OpenAI-compatible)."""
        import requests

        body = {
            "model": self.llm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "temperature": 0.7,
            "max_tokens": 4096
        }

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.llm_api_key}"
        }

        r = requests.post(self.llm_endpoint, json=body, headers=headers, timeout=120)
        r.raise_for_status()
        data = r.json()

        # Extract content (OpenAI-compatible)
        choices = data.get('choices', [])
        if choices:
            content = choices[0].get('message', {}).get('content', '')
            if content:
                return content
            text = choices[0].get('text', '')
            if text:
                return text

        # Fallback
        if 'content' in data:
            return data['content']

        return json.dumps(data)

    def _call_llm_with_history(self, system_prompt: str, history_msgs: list, current_user_msg: str) -> str:
        """Call LLM with properly role-labeled conversation history as separate messages."""
        import requests

        messages = [{"role": "system", "content": system_prompt}]

        # Add conversation history with correct roles (skip model_snapshot and summary roles)
        for msg in history_msgs:
            role = msg.role
            content = msg.content
            if role in ('user', 'assistant'):
                messages.append({"role": role, "content": content})
            elif role == 'system':
                # Summary messages — include as system context
                messages.append({"role": "system", "content": content})

        # Add the current user message
        messages.append({"role": "user", "content": current_user_msg})

        body = {
            "model": self.llm_model,
            "messages": messages,
            "temperature": 0.7,
            "max_tokens": 16384
        }

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.llm_api_key}"
        }

        r = requests.post(self.llm_endpoint, json=body, headers=headers, timeout=120)
        r.raise_for_status()
        data = r.json()

        # Extract content
        choices = data.get('choices', [])
        if choices:
            content = choices[0].get('message', {}).get('content', '')
            if content:
                return content
            text = choices[0].get('text', '')
            if text:
                return text
        if 'content' in data:
            return data['content']
        return json.dumps(data)

    # ============================================================
    # Conversation Management (delegated to memory)
    # ============================================================

    def list_conversations(self, project_id: str) -> list[dict]:
        return self.memory.list_conversations(project_id)

    def get_conversation(self, project_id: str, conversation_id: str) -> dict:
        """Get conversation with messages."""
        convs = self.memory.list_conversations(project_id)
        conv = next((c for c in convs if c['id'] == conversation_id), None)
        if not conv:
            return None
        messages = self.memory.get_messages(conversation_id)
        return {
            'id': conversation_id,
            'title': conv.get('title', ''),
            'messages': [
                {'role': m.role, 'content': m.content, 'metadata': m.metadata}
                for m in messages if m.role not in ('model_snapshot',)
            ]
        }

    def delete_conversation(self, conversation_id: str):
        self.memory.delete_conversation(conversation_id)
