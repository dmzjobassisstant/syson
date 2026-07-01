# SysON Element Modification API

## Overview

Direct element modification via GraphQL mutations. All use the `IEditingContextEventHandler` pattern — always active, no WebSocket subscription required.

### Critical: Editing Context ID ≠ Project ID

The `editingContextId` is **not** the project UUID. It is the internal editing context ID returned by `viewer.project.currentEditingContext.id`. Using the project ID will silently fail with `ErrorPayload`.

To get the editing context ID:
```graphql
query {
  viewer {
    project(projectId: "<project-uuid>") {
      currentEditingContext { id }
    }
  }
}
```

## Mutations

### updateElement — Rename / Set Properties

```graphql
mutation {
  updateElement(input: {
    id: "<request-uuid>",
    editingContextId: "<editing-context-id>",
    elementId: "<element-id>",
    newLabel: "Switchgear",
    newShortName: "sw",
    newBody: "Optional documentation text"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `ID!` | Yes | Request UUID |
| `editingContextId` | `ID!` | Yes | Editing context ID (not project ID) |
| `elementId` | `ID!` | Yes | Target element ID |
| `newLabel` | `String` | No | New declared name |
| `newShortName` | `String` | No | New short name (empty string clears it) |
| `newBody` | `String` | No | New documentation/body text |
| `properties` | `[KeyValueInput!]` | No | Arbitrary key-value pairs (name, shortName) |

### deleteElement — Delete Element

Deletes an element and its containing Membership using `DeleteService.deleteFromModel()`.

```graphql
mutation {
  deleteElement(input: {
    id: "<request-uuid>",
    editingContextId: "<editing-context-id>",
    elementId: "<element-id>"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

### addChildElement — Create Typed Child

Creates a new element of the specified type under a parent element. Auto-creates `OwningMembership` and initializes defaults via `ElementInitializerSwitch`.

```graphql
mutation {
  addChildElement(input: {
    id: "<request-uuid>",
    editingContextId: "<editing-context-id>",
    parentElementId: "<parent-element-id>",
    elementType: "PartUsage",
    name: "MyNewPart"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { messages { body level } }
  }
}
```

**Supported element types:** `Package`, `PartUsage`, `PartDefinition`, `AttributeUsage`, `AttributeDefinition`, `FlowConnectionUsage`, `RequirementUsage`, `RequirementDefinition`, `Comment`, `Dependency`, and any other SysML metamodel class name.

### manageRelationship — Add/Remove Relationships

Creates or removes relationships between elements.

```graphql
mutation {
  manageRelationship(input: {
    id: "<request-uuid>",
    editingContextId: "<editing-context-id>",
    relationshipType: "Dependency",
    sourceElementId: "<source-element-id>",
    targetElementIds: ["<target-element-id>"],
    action: "ADD"
  }) {
    __typename
    ... on SuccessPayload { messages { body level } }
    ... on ErrorPayload { messages { body level } }
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `relationshipType` | `String!` | Yes | `Dependency`, `Subclassification`, or `Specialization` |
| `sourceElementId` | `ID!` | Yes | Source element (client for Dependency, specific for Subclassification) |
| `targetElementIds` | `[ID!]!` | Yes | Target elements (suppliers for Dependency, generals for Subclassification) |
| `action` | `String!` | Yes | `ADD` or `REMOVE` |

**Relationship semantics:**
- **Dependency:** source depends on targets (source=client, targets=suppliers)
- **Subclassification:** source is a subclass of targets (requires both to be `Type`)
- **Specialization:** generic specialization (requires both to be `Type`)

## Architecture

All four mutations use `IEditingContextEventHandler` (not `IRepresentationEventHandler`):

| Pattern | Handler Interface | Requires WS Subscription | Examples |
|---------|------------------|------------------------|----------|
| **Direct** | `IEditingContextEventHandler` | No | `createChild`, `createRootObject`, `insertTextualSysMLv2`, **`updateElement`**, **`deleteElement`**, **`addChildElement`**, **`manageRelationship`** |
| **Collaborative** | `IRepresentationEventHandler` | Yes | `renameTreeItem`, `deleteTreeItem`, `editLabel` |

Changes are persisted automatically by the Sirius Web collaborative framework when `ChangeDescription(ChangeKind.SEMANTIC_CHANGE, ...)` is emitted.

## curl Examples

```bash
# Login
TOKEN=$(curl -s -X POST https://syson.bowtie-modeler.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin","password":"admin"}' | jq -r .token)

# Rename an element
curl -s https://syson.bowtie-modeler.com/api/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: UpdateElementInput!) { updateElement(input: $input) { __typename } }", "variables": {"input": {"id": "'$(uuidgen)'", "editingContextId": "<EC_ID>", "elementId": "<ELEMENT_ID>", "newLabel": "NewName"}}}'

# Delete an element
curl -s https://syson.bowtie-modeler.com/api/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: DeleteElementInput!) { deleteElement(input: $input) { __typename } }", "variables": {"input": {"id": "'$(uuidgen)'", "editingContextId": "<EC_ID>", "elementId": "<ELEMENT_ID>"}}}'

# Add a child PartUsage
curl -s https://syson.bowtie-modeler.com/api/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: AddChildElementInput!) { addChildElement(input: $input) { __typename } }", "variables": {"input": {"id": "'$(uuidgen)'", "editingContextId": "<EC_ID>", "parentElementId": "<PARENT_ID>", "elementType": "PartUsage", "name": "MyPart"}}}'

# Add a Dependency relationship
curl -s https://syson.bowtie-modeler.com/api/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: ManageRelationshipInput!) { manageRelationship(input: $input) { __typename } }", "variables": {"input": {"id": "'$(uuidgen)'", "editingContextId": "<EC_ID>", "relationshipType": "Dependency", "sourceElementId": "<SOURCE_ID>", "targetElementIds": ["<TARGET_ID>"], "action": "ADD"}}}'
```
