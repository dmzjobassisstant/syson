# SysON API Reference

Complete reference for the SysON GraphQL and REST API surface. All endpoints documented from the running system introspection.

## Authentication

### Login
```
POST /api/auth/login
Content-Type: application/json

{"email": "admin", "password": "admin"}
```
Returns `{"token": "eyJ...", "email": "admin", "roles": ["superuser"]}`

### Token Refresh
```
POST /api/auth/refresh
Authorization: Bearer <token>
```

### Logout
```
POST /api/auth/logout
Authorization: Bearer <token>
```

All subsequent requests require `Authorization: Bearer <token>` header.

---

## Critical Concept: editingContextId ≠ projectId

The `editingContextId` used in GraphQL mutations is **not** the project UUID. It is an internal ID returned by `viewer.project.currentEditingContext.id`. A mutation using the project ID will silently return `ErrorPayload`.

### How to get the editing context ID

```graphql
query {
  viewer {
    project(projectId: "<project-uuid>") {
      id
      currentEditingContext { id }
    }
  }
}
```

**Note:** The editing context is loaded lazily when the project is opened (via the web UI or by accessing it through GraphQL). After a cold restart, open the project in the browser to load it.

---

## GraphQL API

Endpoint: `POST /api/graphql`

### Queries

Only one root query exists: `viewer`. All data access is nested under it.

```graphql
query {
  viewer {
    id
    language          # Fork extension: viewer.language
    namespaces        # Fork extension: viewer.namespaces
    capabilities      # Fork extension: viewer.capabilities
    projects { edges { node { id name } } }
    project(projectId: "...") {
      currentEditingContext { id }
      representations { edges { node { id label } } }
    }
    editingContext(editingContextId: "...") {
      id
      object(objectId: "...") { __typename ... on Object { label } }
      explorerDescriptions { id label }
      treeDescriptions { id }
    }
  }
}
```

### Mutations — Element Lifecycle

#### createProject
Creates a new project from a template.
```graphql
mutation {
  createProject(input: {
    id: "<uuid>",
    name: "My Project",
    templateId: "sysmlv2",
    libraryIds: [],
    natures: []
  }) {
    __typename
    ... on CreateProjectSuccessPayload { project { id name } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

#### createDocument
Creates a SysML document within an editing context.
```graphql
mutation {
  createDocument(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    name: "MyDocument",
    stereotypeId: "empty_sysmlv2"
  }) {
    __typename
    ... on CreateDocumentSuccessPayload { document { id name kind } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

#### createRootObject
Creates a root element in a document (e.g., a Package).
```graphql
mutation {
  createRootObject(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    documentId: "<doc-id>",
    domainId: "http://www.eclipse.org/syson/sysml",
    rootObjectCreationDescriptionId: "SysMLv2EditService-Package"
  }) {
    __typename
    ... on CreateRootObjectSuccessPayload { object { id label kind } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

#### createChild
Creates a child element using the Sirius Web palette tool system.
```graphql
mutation {
  createChild(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    objectId: "<parent-element-id>",
    childCreationDescriptionId: "SysMLv2EditService-PartUsage"
  }) {
    __typename
    ... on CreateChildSuccessPayload { object { id label kind } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

### Mutations — Direct Element Modification (Fork Extensions)

These four mutations are **custom to this fork**. They use `IEditingContextEventHandler` — always active, no WebSocket subscription required.

#### updateElement
Renames an element or updates its properties.
```graphql
mutation {
  updateElement(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    elementId: "<element-id>",
    newLabel: "Switchgear",
    newShortName: "sw",
    newBody: "Documentation text"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `id` | `ID!` | Yes | Request UUID |
| `editingContextId` | `ID!` | Yes | Editing context ID |
| `elementId` | `ID!` | Yes | Target element ID |
| `newLabel` | `String` | No | New declared name |
| `newShortName` | `String` | No | New short name (empty clears) |
| `newBody` | `String` | No | Documentation comment text |
| `properties` | `[KeyValueInput!]` | No | Key-value pairs (name, shortName) |

#### deleteElement
Deletes an element and its containing Membership.
```graphql
mutation {
  deleteElement(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    elementId: "<element-id>"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

#### addChildElement
Creates a typed child element with auto-initialized defaults.
```graphql
mutation {
  addChildElement(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    parentElementId: "<parent-element-id>",
    elementType: "PartUsage",
    name: "MyPart"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

**Valid elementType values:** `Package`, `PartUsage`, `PartDefinition`, `AttributeUsage`, `AttributeDefinition`, `FlowConnectionUsage`, `RequirementUsage`, `RequirementDefinition`, `Comment`, `Dependency`, `EnumerationDefinition`, `PortDefinition`, `ActionUsage`, `ActionDefinition`, `StateUsage`, `StateDefinition`, `ConstraintUsage`, `AllocationUsage`, and any other SysML metamodel class name.

#### manageRelationship
Adds or removes relationships between elements.
```graphql
mutation {
  manageRelationship(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    relationshipType: "Dependency",
    sourceElementId: "<source-id>",
    targetElementIds: ["<target-id>"],
    action: "ADD"
  }) {
    __typename
    ... on SuccessPayload { messages { body level } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

| relationshipType | Source Role | Target Role | Requires Type |
|-----------------|------------|------------|--------------|
| `Dependency` | Client (depends on) | Supplier | Any Element |
| `Subclassification` | Specific (subclass) | General (superclass) | Both must be `Type` |
| `Specialization` | Specific | General | Both must be `Type` |

**action:** `"ADD"` or `"REMOVE"`

### Mutations — SysMLv2 Code Import

#### insertTextualSysMLv2
Imports SysMLv2 textual code into an existing element. Uses the SysMLv2 parser to convert text to EMF elements.

```graphql
mutation {
  insertTextualSysMLv2(input: {
    id: "<uuid>",
    editingContextId: "<ec-id>",
    objectId: "<parent-element-id>",
    textualContent: "part def Vehicle { attribute speed : Integer; }"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

**Capabilities:**
- Parses full SysMLv2 textual syntax (packages, definitions, usages, relationships)
- Can insert multi-line, multi-element text
- Creates EMF elements as children of the specified parent element
- Supports all SysMLv2 constructs (part, port, flow, connection, requirement, etc.)

**Limitations:**
- Must target an existing parent element (typically a Package or document root)
- Cannot create root-level packages without a parent container
- Parse errors are returned as messages in the payload
- Proxy resolution errors occur when referencing undefined types (e.g., `part p : FakeType;`)

**This mutation CAN import a full model from SysMLv2 code** — pass the complete model text as `textualContent` targeting the root Namespace or a Package.

### Mutations — Collaborative (Require WebSocket Subscription)

These mutations require an active `IRepresentationEventHandler` processor, which only exists when a tree/form/diagram subscription is open via WebSocket.

| Mutation | Purpose | Why It Fails Without WS |
|----------|---------|------------------------|
| `renameTreeItem` | Rename in tree view | No representation event processor |
| `deleteTreeItem` | Delete from tree view | No representation event processor |
| `editLabel` | Edit node label | No representation event processor |
| `editTextfield` | Edit text field | No representation event processor |

**Use the fork-extended mutations (`updateElement`, `deleteElement`) instead** — they perform the same operations without requiring a subscription.

### Mutations — Diagram Operations

| Mutation | Purpose |
|----------|---------|
| `createRepresentation` | Create a new diagram/tree representation |
| `deleteRepresentation` | Delete a representation |
| `dropNode` / `dropOnDiagram` | Drop elements onto diagram |
| `deleteFromDiagram` | Delete element from diagram |
| `reconnectEdge` | Reconnect a diagram edge |
| `arrangeAll` | Auto-arrange diagram layout |
| `hideDiagramElement` / `fadeDiagramElement` | Hide/fade elements |
| `invokeSingleClickOnDiagramElementTool` | Invoke a diagram palette tool |

### Mutations — Project Management

| Mutation | Purpose |
|----------|---------|
| `createProject` | Create project from template |
| `createProjectFromTemplate` | Create from existing template |
| `renameProject` | Rename a project |
| `deleteProject` | Delete a project |
| `uploadProject` | Upload a `.sysml` file as new project |
| `uploadDocument` | Upload a document into existing project |
| `importLibraries` | Import standard libraries |
| `publishLibraries` | Publish custom libraries |

### Mutations — Undo/Redo

```graphql
mutation { undo(input: { id: "<uuid>", editingContextId: "<ec-id>", representationId: "<rep-id>" }) { __typename } }
mutation { redo(input: { id: "<uuid>", editingContextId: "<ec-id>", representationId: "<rep-id>" }) { __typename } }
```

---

## REST API

### SysML Element Access (Sirius Web upstream)

```
GET /api/rest/projects/{projectId}/commits/{projectId}/elements
GET /api/rest/projects/{projectId}/commits/{projectId}/elements/{elementId}
```

Returns JSON-LD elements. **Read-only** — HTTP 405 on POST/PUT/PATCH/DELETE.

### Authentication REST (Fork Extension)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/auth/login` | POST | Login, get JWT token |
| `/api/auth/refresh` | POST | Refresh expired token |
| `/api/auth/logout` | POST | Invalidate session |
| `/api/v1/user/me` | GET | Get current user profile |
| `/api/v1/user/me/password` | PUT | Change password |
| `/api/v1/user/me/projects` | GET | Get user's projects |
| `/api/v1/user/ping` | GET | Health check |
| `/api/v1/user/password/reset/request` | POST | Request password reset |
| `/api/v1/user/password/reset/complete` | POST | Complete password reset |

### Admin REST (Fork Extension)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/user/admin/users` | GET | List all users |
| `/api/v1/user/admin/users` | POST | Create user |
| `/api/v1/user/admin/users/{id}/deactivate` | PUT | Deactivate user |
| `/api/v1/user/admin/users/{id}/reactivate` | PUT | Reactivate user |
| `/api/v1/user/admin/users/{id}/password` | PUT | Reset user password |
| `/api/v1/user/admin/tenants/{id}/roles/{userId}` | PUT | Assign tenant role |
| `/api/v1/user/admin/projects/{id}/members` | GET | List project members |
| `/api/v1/user/admin/projects/{id}/members` | POST | Add project member |
| `/api/v1/user/admin/projects/{id}/members/{userId}` | DELETE | Remove project member |
| `/api/v1/user/admin/audit/events` | GET | Query audit events |
| `/api/v1/user/admin/audit-trail` | GET | Full audit trail |
| `/api/v1/user/admin/audit-trail/stats` | GET | Audit statistics |

### Element History REST (Fork Extension)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/projects/{id}/elements/{stableId}/history` | GET | Element change history |
| `/api/v1/projects/{id}/branches/{branchId}/elements` | GET | Elements on a branch |
| `/api/v1/projects/{id}/branches/{branchId}/elements/{id}` | GET | Single element on branch |
| `/api/v1/projects/{id}/branches/{branchId}/elements/{id}/children` | GET | Element children |
| `/api/v1/projects/{id}/branches/{branchId}/relationships` | GET | All relationships |
| `/api/v1/projects/{id}/branches/{branchId}/export` | GET | Export model as SysML |
| `/api/v1/projects/{id}/branches/{branchId}/diagrams/{id}/nodes` | GET | Diagram nodes |

### Version Control REST (Fork Extension)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/projects/{id}/branches` | GET/POST | List/create branches |
| `/api/v1/projects/{id}/branches/{id}/commits` | GET/POST | List/create commits |
| `/api/v1/projects/{id}/branches/{id}/commits/{id}/diff` | GET | Commit diff |
| `/api/v1/projects/{id}/baselines` | GET | List baselines |
| `/api/v1/projects/{id}/branches/{id}/baselines` | GET/POST | List/create baselines |
| `/api/v1/projects/{id}/save` | POST | Save with commit message |
| `/api/v1/projects/{id}/branches/{id}/lock` | GET/POST/DELETE | Branch locks |
| `/api/v1/projects/{id}/elements/{id}/lock` | GET/POST/DELETE | Element locks |
| `/api/v1/projects/{id}/version-control/overview` | GET | VC overview |
| `/api/v1/projects/{id}/version-control/tree` | GET | GitGraph tree |
| `/api/v1/projects/{id}/version-control/compare` | GET | Branch comparison |
| `/api/v1/projects/{id}/tags` | GET | List tags |

### Chat/AI REST (Fork Extension)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/projects/{id}/chat/execute` | POST | Execute AI command (stub) |
| `/api/v1/sysml/validate` | POST | Validate SysMLv2 code |

---

## Architecture: Direct vs Collaborative Mutations

| Pattern | Handler Interface | Requires WS Subscription | Examples |
|---------|------------------|------------------------|----------|
| **Direct** | `IEditingContextEventHandler` | **No** | `createChild`, `createRootObject`, `createDocument`, `insertTextualSysMLv2`, `updateElement`, `deleteElement`, `addChildElement`, `manageRelationship` |
| **Collaborative** | `IRepresentationEventHandler` | **Yes** | `renameTreeItem`, `deleteTreeItem`, `editLabel`, `editTextfield` |

Direct mutations work at any time — they dispatch to the editing context event processor which is always registered. Collaborative mutations require an active representation subscription (tree/form/diagram) to have registered the representation event processor.

**For API/script/LLM access:** Always use direct mutations. They are the only ones that work without a browser WebSocket connection.

---

## SysMLv2 Code Import

### Can the API import a model from SysMLv2 code?

**Yes.** The `insertTextualSysMLv2` mutation accepts arbitrary SysMLv2 textual content and parses it into EMF elements. It uses the SysMLv2 ANTLR parser (`SysmlToAst`) and the `ASTTransformer` to convert text into model elements.

### How to import a full model

1. Create a project and document
2. Get the editing context ID
3. Find the root Namespace or create a Package
4. Call `insertTextualSysMLv2` with the full model text

```bash
# 1. Create project (or use existing)
# 2. Get editing context
EC_ID=$(get_editing_context_id $PROJECT_ID)
# 3. Find root namespace
ROOT_ID=$(get_root_namespace $EC_ID)
# 4. Import model
curl -X POST .../api/graphql -d '{
  "query": "mutation($input: InsertTextualSysMLv2Input!) {
    insertTextualSysMLv2(input: $input) { __typename }
  }",
  "variables": {
    "input": {
      "id": "'$UUID'",
      "editingContextId": "'$EC_ID'",
      "objectId": "'$ROOT_ID'",
      "textualContent": "package MyModel { part def Vehicle { attribute speed : Integer; } part car : Vehicle; }"
    }
  }
}'
```

### What SysMLv2 text is supported?

The parser supports the full SysMLv2 textual notation including:
- Package and namespace declarations
- Part/item/port/attribute/flow/connection definitions and usages
- Inheritance (`:>`) and redefinition (`:>>`)
- Multiplicity bounds (`[0..*]`)
- Default values (`= 42`)
- Documentation (`doc /* ... */`)
- Comments (`// ...`)
- Requirements, actions, states, constraints
- Imports and aliases
- Visibility modifiers (public/private/protected)

See `SYSML_V2_CODE_RULES.md` for the complete syntax reference.
