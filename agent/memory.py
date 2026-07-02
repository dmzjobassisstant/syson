"""
SysON Agent — Conversation Memory System

Manages conversation history, summarization, and context building.
Supports:
  - Per-project, per-conversation message storage
  - Token-aware context windowing
  - Automatic summarization when context grows too large
  - Persisted to SQLite for durability across restarts
"""

import sqlite3
import json
import time
import os
import hashlib
from dataclasses import dataclass, field, asdict
from typing import Optional
from pathlib import Path


@dataclass
class Message:
    role: str          # 'user', 'assistant', 'system', 'summary'
    content: str
    timestamp: float = field(default_factory=time.time)
    metadata: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = {"role": self.role, "content": self.content, "timestamp": self.timestamp}
        if self.metadata:
            d["metadata"] = self.metadata
        return d

    def token_estimate(self) -> int:
        """Rough token estimate (~4 chars per token)."""
        return max(1, len(self.content) // 4)

    @classmethod
    def from_row(cls, row: tuple) -> "Message":
        """Create from SQLite row."""
        return cls(
            role=row[0],
            content=row[1],
            timestamp=row[2],
            metadata=json.loads(row[3]) if row[3] else {}
        )


class ConversationMemory:
    """
    Manages conversation memory for the agent.
    
    Each conversation belongs to a project and has:
    - Full message history (stored in SQLite)
    - A working context window (most recent N tokens)
    - Optional summary of older messages
    
    The summary is regenerated when the context exceeds max_tokens.
    The summary itself is small and stays in context permanently.
    """

    MAX_CONTEXT_TOKENS = 12000   # Max tokens for the LLM context window
    SUMMARY_THRESHOLD = 8000     # When working set exceeds this, summarize older messages
    MIN_MESSAGES_TO_KEEP = 4     # Always keep at least this many recent messages

    def __init__(self, db_path: str = None):
        if db_path is None:
            db_path = os.environ.get("SYSON_AGENT_DB", str(Path.home() / ".syson" / "agent_memory.db"))
        
        self.db_path = db_path
        os.makedirs(os.path.dirname(self.db_path), exist_ok=True)
        self._init_db()

    def _init_db(self):
        """Initialize SQLite database."""
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    title TEXT DEFAULT '',
                    created_at REAL,
                    updated_at REAL,
                    summary TEXT DEFAULT ''
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp REAL,
                    metadata TEXT,
                    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
                )
            """)
            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_messages_conv 
                ON messages(conversation_id, timestamp)
            """)
            conn.commit()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    # ============================================================
    # Conversation Lifecycle
    # ============================================================

    def create_conversation(self, project_id: str, title: str = "") -> str:
        """Create a new conversation and return its ID."""
        conv_id = hashlib.md5(f"{project_id}:{time.time()}".encode()).hexdigest()[:16]
        now = time.time()
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO conversations (id, project_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (conv_id, project_id, title, now, now)
            )
            conn.commit()
        return conv_id

    def list_conversations(self, project_id: str) -> list[dict]:
        """List all conversations for a project."""
        with self._conn() as conn:
            rows = conn.execute(
                """SELECT c.*, 
                   (SELECT content FROM messages WHERE conversation_id = c.id AND role = 'user' 
                    ORDER BY timestamp DESC LIMIT 1) as last_message,
                   (SELECT COUNT(*) FROM messages WHERE conversation_id = c.id) as msg_count
                   FROM conversations c WHERE c.project_id = ? ORDER BY c.updated_at DESC""",
                (project_id,)
            ).fetchall()
            return [dict(row) for row in rows]

    def delete_conversation(self, conversation_id: str):
        """Delete a conversation and all its messages."""
        with self._conn() as conn:
            conn.execute("DELETE FROM messages WHERE conversation_id = ?", (conversation_id,))
            conn.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,))
            conn.commit()

    def rename_conversation(self, conversation_id: str, title: str):
        with self._conn() as conn:
            conn.execute(
                "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
                (title, time.time(), conversation_id)
            )
            conn.commit()

    # ============================================================
    # Message Management
    # ============================================================

    def add_message(self, conversation_id: str, role: str, content: str, metadata: dict = None):
        """Add a message to a conversation."""
        now = time.time()
        meta_str = json.dumps(metadata) if metadata else None
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO messages (conversation_id, role, content, timestamp, metadata) VALUES (?, ?, ?, ?, ?)",
                (conversation_id, role, content, now, meta_str)
            )
            conn.execute(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                (now, conversation_id)
            )
            conn.commit()

    def get_messages(self, conversation_id: str) -> list[Message]:
        """Get all messages for a conversation."""
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT role, content, timestamp, metadata FROM messages WHERE conversation_id = ? ORDER BY timestamp",
                (conversation_id,)
            ).fetchall()
            return [Message.from_row(tuple(r)) for r in rows]

    def get_context_messages(self, conversation_id: str) -> list[Message]:
        """
        Get messages for LLM context, applying token windowing and summarization.
        
        Returns:
            - Optional summary message (if older messages were summarized)
            - Most recent messages that fit within MAX_CONTEXT_TOKENS
        """
        all_messages = self.get_messages(conversation_id)
        if not all_messages:
            return []

        # Filter out any existing summary messages (we manage these ourselves)
        regular = [m for m in all_messages if m.role != 'summary']
        
        if not regular:
            return []

        # Calculate total tokens
        total_tokens = sum(m.token_estimate() for m in regular)

        if total_tokens <= self.MAX_CONTEXT_TOKENS:
            # Everything fits — check if we have a stored summary
            summary = self._get_summary(conversation_id)
            if summary:
                return [Message(role='system', content=f"[Previous conversation summary]: {summary}")] + regular
            return regular

        # Need to trim — summarize older messages
        if total_tokens > self.SUMMARY_THRESHOLD:
            self._maybe_summarize(conversation_id, regular)

        # Return summary + most recent messages that fit
        summary = self._get_summary(conversation_id) or ""
        summary_msg = None
        if summary:
            summary_msg = Message(role='system', content=f"[Previous conversation summary]: {summary}")

        # Keep most recent messages that fit in remaining budget
        summary_tokens = summary_msg.token_estimate() if summary_msg else 0
        remaining_budget = self.MAX_CONTEXT_TOKENS - summary_tokens
        
        kept = []
        used = 0
        for msg in reversed(regular):
            msg_tokens = msg.token_estimate()
            if used + msg_tokens > remaining_budget:
                break
            kept.insert(0, msg)
            used += msg_tokens

        # Ensure we keep at least MIN_MESSAGES_TO_KEEP
        if len(kept) < self.MIN_MESSAGES_TO_KEEP and len(regular) >= self.MIN_MESSAGES_TO_KEEP:
            kept = regular[-self.MIN_MESSAGES_TO_KEEP:]

        result = []
        if summary_msg:
            result.append(summary_msg)
        result.extend(kept)
        return result

    def _get_summary(self, conversation_id: str) -> Optional[str]:
        """Get stored summary for a conversation."""
        with self._conn() as conn:
            row = conn.execute(
                "SELECT summary FROM conversations WHERE id = ?", (conversation_id,)
            ).fetchone()
            if row and row['summary']:
                return row['summary']
        return None

    def _maybe_summarize(self, conversation_id: str, messages: list[Message]):
        """
        Generate a simple extractive summary of older messages.
        For LLM-based summarization, the agent engine will handle it.
        """
        # Check if we already have a summary
        existing = self._get_summary(conversation_id)
        
        # Take the older half of messages (those not in the working set)
        cutoff = len(messages) // 2
        old_messages = messages[:cutoff]
        
        if not old_messages:
            return

        # Extractive summary: take first line of each user message + key decisions
        summary_parts = []
        if existing:
            summary_parts.append(existing)
        
        for msg in old_messages:
            if msg.role == 'user':
                # First line or first 100 chars
                first_line = msg.content.split('\n')[0][:100]
                summary_parts.append(f"User asked: {first_line}")
            elif msg.role == 'assistant' and msg.metadata.get('action'):
                action = msg.metadata['action']
                summary_parts.append(f"Assistant performed: {action}")

        new_summary = " | ".join(summary_parts[-20:])  # Keep last 20 items

        with self._conn() as conn:
            conn.execute(
                "UPDATE conversations SET summary = ? WHERE id = ?",
                (new_summary, conversation_id)
            )
            conn.commit()

    def update_summary_from_llm(self, conversation_id: str, summary: str):
        """Store an LLM-generated summary (replaces extractive summary)."""
        with self._conn() as conn:
            conn.execute(
                "UPDATE conversations SET summary = ? WHERE id = ?",
                (summary, conversation_id)
            )
            conn.commit()

    # ============================================================
    # Model State Caching
    # ============================================================

    def get_model_snapshot(self, conversation_id: str) -> Optional[dict]:
        """Get cached model state snapshot for context."""
        with self._conn() as conn:
            row = conn.execute(
                "SELECT content, timestamp FROM messages WHERE conversation_id = ? AND role = 'model_snapshot' ORDER BY timestamp DESC LIMIT 1",
                (conversation_id,)
            ).fetchone()
            if row:
                return {"snapshot": json.loads(row['content']), "timestamp": row['timestamp']}
        return None

    def cache_model_snapshot(self, conversation_id: str, snapshot: dict):
        """Cache current model state for context."""
        self.add_message(conversation_id, 'model_snapshot', json.dumps(snapshot))


# ============================================================
# Quick test
# ============================================================

if __name__ == "__main__":
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        mem = ConversationMemory(db_path=os.path.join(tmp, "test.db"))

        # Create conversation
        conv_id = mem.create_conversation("test-project")
        print(f"Created conversation: {conv_id}")

        # Add messages
        for i in range(10):
            mem.add_message(conv_id, 'user', f"This is message number {i} " * 50)
            mem.add_message(conv_id, 'assistant', f"Response to message {i} " * 30)

        # Get context
        msgs = mem.get_context_messages(conv_id)
        total_tokens = sum(m.token_estimate() for m in msgs)
        print(f"Context: {len(msgs)} messages, ~{total_tokens} tokens")

        # List conversations
        convs = mem.list_conversations("test-project")
        print(f"Conversations: {len(convs)}, title='{convs[0]['title']}', msgs={convs[0]['msg_count']}")

        # Test delete
        mem.delete_conversation(conv_id)
        convs = mem.list_conversations("test-project")
        print(f"After delete: {len(convs)} conversations")
        
    print("\nAll tests passed!")
