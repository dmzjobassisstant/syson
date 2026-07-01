# SysON Element Modification API

## Overview

Direct element modification via GraphQL `updateElement` mutation. This is an `IEditingContextEventHandler` — always active, no WebSocket subscription required.

## Mutations

### updateElement — Rename / Set Properties

Renames an element or updates its properties directly. Works without a tree subscription.

```graphql
mutation {
  updateElement(input: {
    id: "<request-uuid>",
    editingContextId: "<editing-context-id>",
    elementId: "<element-id>",
    newLabel: "Switchgear"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { message }
  }
}
```

### Parameters

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `ID!` | Yes | Request UUID (any UUID) |
| `editingContextId` | `ID!` | Yes | Editing context ID (from `currentEditingContext`) |
| `elementId` | `ID!` | Yes | Element ID to modify (from REST API `elementId` field or GraphQL `object().id`) |
| `newLabel` | `String` | No | New name (sets `declaredName`) |
| `newShortName` | `String` | No | New short name (sets `declaredShortName`, empty string clears it) |
| `newBody` | `String` | No | Description/documentation text (creates/updates a Comment) |
| `properties` | `[KeyValueInput!]` | No | Arbitrary key-value pairs (supports `name` and `shortName` keys) |

### Response

Returns `SuccessPayload` (with the request `id`) or `ErrorPayload` (with `message`).

## curl Examples

### Rename an element

```bash
curl -X POST https://syson.damuza-consulting.com/api/graphql \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation{updateElement(input:{id:\"<uuid>\",editingContextId:\"<ec>\",elementId:\"<eid>\",newLabel:\"Switchgear\"}){__typename ...on SuccessPayload{id} ...on ErrorPayload{message}}}"}'
```

### Set body/description

```bash
curl -X POST https://syson.damuza-consulting.com/api/graphql \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation{updateElement(input:{id:\"<uuid>\",editingContextId:\"<ec>\",elementId:\"<eid>\",newBody:\"This is the main power distribution unit.\"}){__typename ...on SuccessPayload{id} ...on ErrorPayload{message}}}"}'
```

### Combined update (name + short name + body)

```graphql
mutation {
  updateElement(input: {
    id: "abc-123",
    editingContextId: "ec-456",
    elementId: "elem-789",
    newLabel: "Switchgear",
    newShortName: "SWG",
    newBody: "Main power distribution"
  }) {
    __typename
    ... on SuccessPayload { id }
    ... on ErrorPayload { message }
  }
}
```

## How It Works

The `updateElement` mutation implements `IEditingContextEventHandler` (not `IRepresentationEventHandler`). This means:

- **Always active**: The editing context event processor is always running while the backend is up
- **No WebSocket needed**: Unlike `renameTreeItem`, no tree/form subscription needs to be open
- **Direct model modification**: Changes are applied to the EMF model immediately and persisted
- **Visible in SysON**: After a page refresh or tree reload, changes appear in the SysON explorer

### Why renameTreeItem times out but updateElement doesn't

`renameTreeItem` implements `IRepresentationEventHandler` — it requires a representation event processor that only exists when a client has an active `explorerEvent` WebSocket subscription. Without it, the backend logs `"No representation event processor found"` and times out after 5 seconds.

`updateElement` implements `IEditingContextEventHandler` — it uses the editing context event processor which is always alive.

## Other Working Mutations

### Create elements (direct, always works)

- `createRootObject` — creates a root element in a document
- `createChild` — creates a child element under a parent
- `createDocument` — creates a new document
- `insertTextualSysMLv2` — inserts SysMLv2 text into a container element

### Read elements

- `GET /api/rest/projects/{id}/commits/{id}/elements` — returns all elements with full hierarchy
- `GET /api/rest/projects/{id}/commits/{id}/elements/{elementId}` — single element
- `GET /api/v1/projects/{id}/branches/{branchId}/elements` — element DTOs from sidecar persistence
- GraphQL `object(objectId: "...")` — single element via GraphQL
