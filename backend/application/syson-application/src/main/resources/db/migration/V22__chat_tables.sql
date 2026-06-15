-- V22: LLM Chat tables
-- Creates the conversation and message tables for the LLM Chat backend.

CREATE TABLE IF NOT EXISTS syson_chat_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    title VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID
);

CREATE TABLE IF NOT EXISTS syson_chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES syson_chat_conversations(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    changes JSONB,
    executed BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_conversations_project ON syson_chat_conversations(project_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation ON syson_chat_messages(conversation_id, created_at ASC);
