# SysML Syntax Validator + LLM Chat / Model Generation Pipeline

**Date:** 2026-06-15  
**Source:** Ported from sysmlv2-platform pilot (`/root/robochacho/Systems/sysmlv2-platform/`)  
**Target:** SysON fork at `/root/syson-fork`

---

## 1. Overview

Bring two capabilities from the sysmlv2-platform pilot into SysON:

| # | Capability | Description |
|---|---|---|
| 1 | **SysML Syntax Validator** | Validate SysML v2 textual syntax against OMG rules |
| 2 | **LLM Chat Pipeline** | Chat with LLM to generate/modify SysML models |

---

## 2. SysML Syntax Validator

### 2.1 Source

The validation algorithm lives in the TypeScript pilot at:

```
sysmlv2-platform/packages/engine/src/validationRules.ts
sysmlv2-platform/scripts/validate-omg-sysml.sh
```

### 2.2 Rules to Port (15 rules)

| # | Pattern | Message |
|---|---|---|
| 1 | `Real` without `import ScalarValues::*` | Models using Real must declare `private import ScalarValues::*;` |
| 2 | `value Real;` | Use `import ScalarValues::*` instead |
| 3 | `enum Name { a,b }` (not `enum def`) | Use `enum def Name { ... }` |
| 4 | `part def Child : Base` (not `:>`) | Use `part def Child :> Base` |
| 5 | Port direction after `port` | Use `in port name : T` not `port in name : T` |
| 6 | `requirement Name { "text" }` (not `def`) | Use `requirement def ... { doc ... }` |
| 7 | Prototype `actor`/`useCase` shorthand | Omit or use library syntax |
| 8 | Prototype relationship shorthand | Omit `A satisfy B;` style |
| 9 | Prototype `view Name : kind { include }` | Store in diagram metadata instead |
| 10 | Transition with `accept Signal` | Requires typed trigger definitions |

Additional structural checks:
- Balanced braces/brackets  
- Valid keyword usage
- Package/block/part def structure validation

### 2.3 REST Endpoint

```
POST /api/v1/sysml/validate
Content-Type: application/json

{
  "source": "package MyPkg { block MyBlock { ... } }",
  "fileId": "model.sysml"       // optional
}

Response:
{
  "valid": true|false,
  "errors": [
    {
      "severity": "error"|"warning"|"info",
      "line": 1,
      "col": 1,
      "message": "Use enum def Name { ... } not enum Name { a, b }",
      "file": "model.sysml"
    }
  ],
  "errorCount": 1,
  "warningCount": 0
}
```

### 2.4 Implementation

Create `SysmlSyntaxValidator.java` as a Spring `@Service` with:

```java
@Service
public class SysmlSyntaxValidator {
    public record ValidationResult(boolean valid, List<Diagnostic> errors) {}
    public record Diagnostic(String severity, int line, int col, String message, String file) {}

    public ValidationResult validate(String source, String fileId);
}
```

Controller: `SysmlValidationController.java`

```
POST /api/v1/sysml/validate
```

---

## 3. LLM Chat Pipeline

### 3.1 Architecture

```
┌──────────┐    ┌──────────────┐    ┌───────────┐    ┌──────────────┐
│ auth.js  │───▶│ ChatController│───▶│ ChatService│───▶│ LLM Provider │
│ (UI)     │    │ (REST)       │    │ (Orch.)    │    │ (HTTP call)  │
└──────────┘    └──────────────┘    └───────────┘    └──────────────┘
      ▲                                    │
      │                                    ▼
      │              ┌──────────────────────────────┐
      └──────────────│  Change Execution Engine      │
                     │  (Sirius Web GraphQL calls)   │
                     └──────────────────────────────┘
```

### 3.2 System Prompt (injected into every LLM call)

```text
You are a SysML v2 modeling assistant. Generate ONLY valid SysML v2 textual notation.

CRITICAL RULES:
1. Use OMG SysML v2 syntax, not prototype/internal shorthand.
2. Package definitions: `package Name { ... }`
3. Part definitions: `part def Name { ... }`  (NOT `part Name { ... }`)
4. Specialization: `part def Child :> Parent { ... }`
5. Ports: `in port name : Type;`  (direction BEFORE `port`)
6. Enums: `enum def Name { literal Name1; literal Name2; }`
7. Requirements: `requirement def ReqId { doc /* text */ }`
8. Attributes: `attribute name : Type;`
9. Relationships: use OMG syntax (`composition`, `specialization`, etc.)
10. Imports: `private import PackageName::*;`
11. Transitions: `transition t first A then B;` (without `accept Signal`)
12. Do NOT generate: `actor`, `useCase` as standalone, `value Real;`, `view Name : kind { }`, prototype relationship shorthand.
13. When modifying an existing model, return ONLY a JSON array of change operations, NOT full model text.

Allowed change operations:
{
  "operation": "CREATE",
  "parentId": "element-id",
  "elementType": "part_def|block|port|attribute|requirement|enum|...",
  "name": "ElementName",
  "properties": { ... }
}
{
  "operation": "UPDATE",
  "targetId": "element-id",
  "properties": { "name": "NewName" }
}
{
  "operation": "DELETE",
  "targetId": "element-id"
}
{
  "operation": "ADD_RELATIONSHIP",
  "sourceId": "element-id",
  "targetId": "element-id",
  "relationshipType": "composition|specialization|satisfy|..."
}
```

### 3.3 REST Endpoints

#### 3.3.1 Generate Model from Prompt

```
POST /api/v1/projects/{projectId}/chat/generate

Body:
{
  "prompt": "Create a package called Powertrain with a part def Motor...",
  "conversationId": "optional-existing-conversation-id",
  "loadAsLibrary": false
}

Response:
{
  "conversationId": "uuid",
  "message": "Generated model: ...",
  "sysmlText": "package Powertrain { ... }",
  "validationResult": { "valid": true, "errors": [] },
  "changes": [ { "operation": "CREATE", ... } ],
  "executed": false,
  "libraryId": null
}
```

#### 3.3.2 Modify Existing Model

```
POST /api/v1/projects/{projectId}/chat/modify

Body:
{
  "prompt": "Add a torque attribute to the Motor part",
  "conversationId": "optional-existing-conversation-id",
  "branchId": "optional-branch-id"
}

Steps:
1. Serialize current model to textual SysML via syson-sysml-export
2. Construct prompt with model context + instructions + user request
3. Call LLM
4. Parse LLM response into change operations
5. Return changes for user approval (NOT executed yet)

Response:
{
  "conversationId": "uuid",
  "message": "Proposed changes: ...",
  "currentModelText": "package ... { ... }",    // serialized current model
  "changes": [
    { "operation": "CREATE", "parentId": "...", "elementType": "attribute", "name": "torque", "properties": { "typeRef": "Real" } }
  ],
  "executed": false
}
```

#### 3.3.3 Execute Approved Changes

```
POST /api/v1/projects/{projectId}/chat/execute

Body:
{
  "conversationId": "uuid",
  "branchId": "optional-branch-id",
  "changes": [
    { "operation": "CREATE", "parentId": "...", ... }
  ]
}

Response:
{
  "results": [
    { "operation": "CREATE", "status": "success", "newElementId": "uuid" },
    { "operation": "CREATE", "status": "failed", "error": "Parent not found" }
  ],
  "successCount": 1,
  "failureCount": 1
}
```

#### 3.3.4 List Conversations

```
GET /api/v1/projects/{projectId}/chat/conversations

Response:
{
  "conversations": [
    { "id": "uuid", "title": "Create Powertrain", "createdAt": "...", "messageCount": 5 }
  ]
}
```

### 3.4 Backend Services

| Service | Package | Purpose |
|---|---|---|
| `ChatController` | `org.eclipse.syson.chat` | REST endpoints |
| `ChatService` | `org.eclipse.syson.chat` | Orchestration |
| `ModelSerializationService` | `org.eclipse.syson.chat` | Model → textual SysML |
| `ChangeExecutionService` | `org.eclipse.syson.chat` | Apply changes to Sirius Web |
| `LlmClientService` | `org.eclipse.syson.chat` | LLM HTTP call abstraction |
| `SysmlSyntaxValidator` | `org.eclipse.syson.chat` | Validation |

### 3.5 LLM Client

```java
@Service
public class LlmClientService {
    // Configurable provider
    // Uses Spring RestTemplate or WebClient
    // Reads LLM config from application.properties or project settings

    public LlmResponse chat(List<LlmMessage> messages, String model);
    
    public record LlmMessage(String role, String content) {}
    public record LlmResponse(String content, String model, int tokenCount) {}
}
```

Configuration (application.properties):
```properties
syson.llm.provider=openai
syson.llm.endpoint=https://api.openai.com/v1/chat/completions
syson.llm.api-key=${LLM_API_KEY:}
syson.llm.model=gpt-4
syson.llm.max-tokens=4096
syson.llm.temperature=0.2
```

### 3.6 Change Execution Engine

The `ChangeExecutionService` maps change operations to Sirius Web GraphQL mutations:

| Operation | Sirius Web Action |
|---|---|
| CREATE | `createChild` / `createRootObject` mutation |
| UPDATE | `renameElement` / `editProperties` mutation |
| DELETE | `deleteFromModel` mutation |
| ADD_RELATIONSHIP | `createChild` mutation with relationship type |

Since Sirius Web uses GraphQL, the engine must:
1. Authenticate with the user's JWT token
2. Call Sirius Web GraphQL endpoint
3. Parse responses
4. Handle errors and rollback

### 3.7 Database Tables

```sql
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
    role VARCHAR(20) NOT NULL,           -- 'user' | 'assistant' | 'system'
    content TEXT NOT NULL,
    changes JSONB,                        -- proposed changes
    executed BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now()
);
```

---

## 4. Frontend UI (auth.js)

### 4.1 Entry Point

A "Chat" button in the user bar next to Save/Diff buttons.

### 4.2 Chat Modal

Full-screen modal overlay with:
- **Left panel**: Conversation history (list of past messages)
- **Right panel**: Current conversation
  - Messages (user/assistant bubbles)
  - Input area with text field + Send button
  - "Load as Library" checkbox (for generation)
  - "Current Model Context" indicator

### 4.3 Change Approval Panel

After a "modify" response, show:
- List of proposed changes (one per row)
- Each row: operation type icon, element name, details, checkbox
- "Select All" / "Deselect All" buttons
- "Execute Approved Changes" button
- Progress bar during execution
- Results summary after execution

### 4.4 Styling

Consistent with SysON branding:
- Colors: daintree (#261e58) primary, Roboto font
- 4px border radius
- MUI-style shadows
- Responsive (full-screen on mobile, centered dialog on desktop)

### 4.5 Flow

```
User clicks "Chat" → Modal opens
  ├── "Generate new model"
  │   ├── User enters prompt
  │   ├── [optional] Check "Load as Library"
  │   ├── Send → LLM generates SysML text
  │   ├── Validation runs, errors shown
  │   ├── User can edit/correct text
  │   ├── Click "Insert into Model"
  │   └── Model elements created, modal closes
  │
  └── "Modify existing model"
      ├── User enters modification request
      ├── Send → Current model serialized → LLM
      ├── Changes shown in approval panel
      ├── User approves/rejects individual changes
      ├── Click "Execute" → Changes applied sequentially
      └── Results shown, Sirius Web editor refreshes
```

---

## 5. Automated Testing

### 5.1 Unit Tests (JUnit)

| Test Class | Tests | Coverage |
|---|---|---|
| `SysmlSyntaxValidatorTest` | 15+ | Validation rules, edge cases |
| `ChatServiceTest` | 10+ | Prompt construction, response parsing |
| `ChangeExecutionServiceTest` | 8+ | Operation mapping, error handling |
| `ModelSerializationServiceTest` | 5+ | Model → text roundtrip |

### 5.2 Integration Tests

```
POST /api/v1/sysml/validate  →  test valid/invalid SysML
POST /api/v1/projects/{id}/chat/generate  →  test generation flow
POST /api/v1/projects/{id}/chat/modify  →  test modification flow
POST /api/v1/projects/{id}/chat/execute  →  test execution
GET  /api/v1/projects/{id}/chat/conversations  →  test listing
```

---

## 6. Implementation Order

| Phase | Item | Dependencies |
|---|---|---|
| 1 | `SysmlSyntaxValidator.java` + `SysmlValidationController.java` | None |
| 2 | Chat DB schema (V22 migration) | Phase 1 |
| 3 | `LlmClientService.java` + `ModelSerializationService.java` | Phase 1 |
| 4 | `ChatService.java` + `ChangeExecutionService.java` | Phase 2, 3 |
| 5 | `ChatController.java` | Phase 4 |
| 6 | Frontend chat UI in auth.js | Phase 5 REST endpoints |
| 7 | Tests (unit + integration) | Phase 5 |
| 8 | Build + deploy + E2E verification | All phases |

---

## 7. Files to Create

```
backend/application/syson-application/src/main/java/org/eclipse/syson/chat/
├── SysmlSyntaxValidator.java
├── SysmlValidationController.java
├── ChatController.java
├── ChatService.java
├── ModelSerializationService.java
├── ChangeExecutionService.java
├── LlmClientService.java
├── entity/
│   ├── ChatConversationEntity.java
│   └── ChatMessageEntity.java
├── repository/
│   ├── ChatConversationRepository.java
│   └── ChatMessageRepository.java
└── dto/
    ├── ValidateRequest.java
    ├── ValidateResponse.java
    ├── ChatGenerateRequest.java
    ├── ChatModifyRequest.java
    ├── ChatExecuteRequest.java
    ├── ChatResponse.java
    ├── ChangeOperation.java
    └── ConversationDto.java

backend/application/syson-application/src/test/java/org/eclipse/syson/chat/
├── SysmlSyntaxValidatorTest.java
├── ChatServiceTest.java
├── ChangeExecutionServiceTest.java
└── ChatIntegrationTest.java
```
